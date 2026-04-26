package com.my.finmon;

import android.content.Context;

import androidx.annotation.NonNull;

import com.my.finmon.data.FinMonDatabase;
import com.my.finmon.data.remote.frankfurter.FrankfurterClient;
import com.my.finmon.data.remote.frankfurter.FrankfurterService;
import com.my.finmon.data.remote.nbu.NbuClient;
import com.my.finmon.data.remote.nbu.NbuService;
import com.my.finmon.data.remote.yahoo.YahooClient;
import com.my.finmon.data.remote.yahoo.YahooService;
import com.my.finmon.data.repository.ImportExportRepository;
import com.my.finmon.data.repository.MarketDataRepository;
import com.my.finmon.data.repository.PortfolioRepository;
import com.squareup.moshi.Moshi;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

/**
 * Manual DI container — one instance per process, lazily built via double-checked locking.
 * Warmed from {@link FinMonApplication#onCreate()} so the DB and repositories are ready
 * before any Activity/Fragment/Worker touches them. Accessed via
 * {@code ServiceLocator.get(context)}.
 *
 * DB I/O runs on a single-thread executor: SQLite serializes writes anyway, and a single
 * thread gives predictable ordering (a trade recorded before a query will be visible to it).
 * Network I/O goes out through a shared OkHttp client (its own internal dispatcher pool).
 */
public final class ServiceLocator {

    private static final String FRANKFURTER_BASE_URL = "https://api.frankfurter.dev/";
    private static final String YAHOO_BASE_URL = "https://query1.finance.yahoo.com/";
    private static final String NBU_BASE_URL = "https://bank.gov.ua/";

    /**
     * Yahoo's chart endpoint silently rejects requests with a missing or default UA. Any
     * non-empty browser-shaped string works; we don't impersonate to avoid policy issues,
     * just identify ourselves.
     */
    private static final String USER_AGENT = "FinMon-Android/1.0";

    private static volatile ServiceLocator INSTANCE;

    private final FinMonDatabase database;
    private final ExecutorService ioExecutor;
    private final ExecutorService viewExecutor;
    private final OkHttpClient httpClient;
    private final PortfolioRepository portfolioRepository;
    private final MarketDataRepository marketDataRepository;
    private final ImportExportRepository importExportRepository;

    private ServiceLocator(@NonNull Context appContext) {
        this.database = FinMonDatabase.get(appContext);

        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "finmon-io");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        // Small pool for ViewModels that block on repo Futures to forward results to
        // LiveData — cannot reuse ioExecutor because Future.get() on the same single
        // thread would deadlock.
        this.viewExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "finmon-view-bridge");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.BASIC
                : HttpLoggingInterceptor.Level.NONE);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(new UserAgentInterceptor(USER_AGENT))
                .addInterceptor(logging)
                .build();

        Moshi moshi = new Moshi.Builder().build();
        Retrofit frankfurterRetrofit = new Retrofit.Builder()
                .baseUrl(FRANKFURTER_BASE_URL)
                .client(httpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build();
        FrankfurterService frankfurterService = frankfurterRetrofit.create(FrankfurterService.class);
        FrankfurterClient frankfurterClient = new FrankfurterClient(frankfurterService);

        Retrofit yahooRetrofit = new Retrofit.Builder()
                .baseUrl(YAHOO_BASE_URL)
                .client(httpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build();
        YahooService yahooService = yahooRetrofit.create(YahooService.class);
        YahooClient yahooClient = new YahooClient(yahooService);

        Retrofit nbuRetrofit = new Retrofit.Builder()
                .baseUrl(NBU_BASE_URL)
                .client(httpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build();
        NbuService nbuService = nbuRetrofit.create(NbuService.class);
        NbuClient nbuClient = new NbuClient(nbuService);

        this.portfolioRepository = new PortfolioRepository(
                database.assetDao(),
                database.eventDao(),
                database.stockPriceDao(),
                database.exchangeRateDao(),
                database.portfolioValueDao(),
                ioExecutor);

        this.marketDataRepository = new MarketDataRepository(
                yahooClient,
                frankfurterClient,
                nbuClient,
                database.stockPriceDao(),
                database.exchangeRateDao(),
                ioExecutor);

        this.importExportRepository = new ImportExportRepository(
                database,
                database.assetDao(),
                database.eventDao(),
                database.stockPriceDao(),
                database.exchangeRateDao(),
                database.portfolioValueDao(),
                portfolioRepository,
                marketDataRepository,
                viewExecutor,
                moshi);
    }

    /** Sets a constant User-Agent on every outgoing request. Yahoo requires non-empty UA. */
    private static final class UserAgentInterceptor implements Interceptor {
        private final String userAgent;
        UserAgentInterceptor(@NonNull String userAgent) { this.userAgent = userAgent; }

        @NonNull
        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Request original = chain.request();
            if (original.header("User-Agent") != null) return chain.proceed(original);
            return chain.proceed(original.newBuilder().header("User-Agent", userAgent).build());
        }
    }

    @NonNull
    public static ServiceLocator get(@NonNull Context anyContext) {
        ServiceLocator local = INSTANCE;
        if (local == null) {
            synchronized (ServiceLocator.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new ServiceLocator(anyContext.getApplicationContext());
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    @NonNull public FinMonDatabase database() { return database; }
    @NonNull public ExecutorService ioExecutor() { return ioExecutor; }
    @NonNull public ExecutorService viewExecutor() { return viewExecutor; }
    @NonNull public PortfolioRepository portfolioRepository() { return portfolioRepository; }
    @NonNull public MarketDataRepository marketDataRepository() { return marketDataRepository; }
    @NonNull public ImportExportRepository importExportRepository() { return importExportRepository; }
}
