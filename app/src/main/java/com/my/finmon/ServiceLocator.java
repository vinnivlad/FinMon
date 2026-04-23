package com.my.finmon;

import android.content.Context;

import androidx.annotation.NonNull;

import com.my.finmon.data.FinMonDatabase;
import com.my.finmon.data.remote.frankfurter.FrankfurterClient;
import com.my.finmon.data.remote.frankfurter.FrankfurterService;
import com.my.finmon.data.remote.stooq.StooqClient;
import com.my.finmon.data.repository.MarketDataRepository;
import com.my.finmon.data.repository.PortfolioRepository;
import com.squareup.moshi.Moshi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
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

    private static volatile ServiceLocator INSTANCE;

    private final FinMonDatabase database;
    private final ExecutorService ioExecutor;
    private final ExecutorService viewExecutor;
    private final OkHttpClient httpClient;
    private final PortfolioRepository portfolioRepository;
    private final MarketDataRepository marketDataRepository;

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

        StooqClient stooqClient = new StooqClient(httpClient, BuildConfig.STOOQ_API_KEY);

        this.portfolioRepository = new PortfolioRepository(
                database.assetDao(),
                database.eventDao(),
                database.stockPriceDao(),
                database.exchangeRateDao(),
                ioExecutor);

        this.marketDataRepository = new MarketDataRepository(
                stooqClient,
                frankfurterClient,
                database.stockPriceDao(),
                database.exchangeRateDao(),
                ioExecutor);
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
}
