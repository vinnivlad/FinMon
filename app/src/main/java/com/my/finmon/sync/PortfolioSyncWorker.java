package com.my.finmon.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.MarketDataRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the {@code stock_price} and {@code exchange_rate} tables current. Each run walks
 * from {@code latest_stored + 1} up to yesterday for every tracked ticker and for the FX
 * pairs. On a fresh DB (nothing stored yet) it bootstraps with a one-week window so there's
 * something to look at while the user is wiring up initial data.
 *
 * Scope NOTE: this is a <em>keep-up-to-date</em> worker, not a backfill. When the user adds
 * a trade dated earlier than anything in {@code stock_price}, we'll trigger a one-shot
 * backfill from the trade date — that work lives in the "add trade" flow, not here.
 *
 * Failures for individual tickers are logged and swallowed — one bad symbol or a
 * provider-side gap shouldn't stop the rest of the sync.
 */
public final class PortfolioSyncWorker extends Worker {

    private static final String TAG = "PortfolioSyncWorker";
    private static final String UNIQUE_NAME = "finmon_sync";
    private static final long INTERVAL_HOURS = 12;
    private static final int BOOTSTRAP_DAYS = 7;

    public PortfolioSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try {
            syncStockPrices(sl, yesterday);
            syncFxRates(sl, yesterday);
            return Result.success();
        } catch (Exception e) {
            // Only structural failures land here — per-ticker errors are caught inside.
            Log.w(TAG, "sync aborted", e);
            return Result.retry();
        }
    }

    private void syncStockPrices(ServiceLocator sl, LocalDate yesterday) {
        StockPriceDao priceDao = sl.database().stockPriceDao();
        MarketDataRepository md = sl.marketDataRepository();
        List<AssetEntity> stocks = sl.database().assetDao().findByType(AssetType.STOCK);

        for (AssetEntity stock : stocks) {
            if (stock.stooqTicker == null || stock.stooqTicker.isBlank()) {
                // Manual-price or unsupported-exchange asset — no remote sync for it.
                continue;
            }
            LocalDate latest = priceDao.latestDate(stock.ticker);
            LocalDate from = (latest != null) ? latest.plusDays(1) : yesterday.minusDays(BOOTSTRAP_DAYS);
            if (from.isAfter(yesterday)) continue;

            try {
                Integer rows = md.fetchAndStoreStockPrices(
                        stock.stooqTicker, stock.ticker, from, yesterday).get();
                Log.i(TAG, "Stooq " + stock.stooqTicker + " " + from + "→" + yesterday + ": " + rows + " rows");
            } catch (Exception e) {
                // Stooq "No data" returns 0 rows cleanly — this catches IOException, API-key
                // rejection, parse errors, and DB failures. Log and continue to the next ticker.
                Log.w(TAG, "Stooq sync failed for " + stock.ticker, e);
            }
        }
    }

    private void syncFxRates(ServiceLocator sl, LocalDate yesterday) {
        ExchangeRateDao fxDao = sl.database().exchangeRateDao();
        // EUR→USD is always present in every Frankfurter response we care about, so it's a
        // reliable bellwether for "what's the most recent FX date we have?".
        LocalDate latest = fxDao.latestDate(Currency.EUR, Currency.USD);
        LocalDate from = (latest != null) ? latest.plusDays(1) : yesterday.minusDays(BOOTSTRAP_DAYS);
        if (from.isAfter(yesterday)) return;

        try {
            Integer rows = sl.marketDataRepository().fetchAndStoreFxRates(from, yesterday).get();
            Log.i(TAG, "Frankfurter " + from + "→" + yesterday + ": " + rows + " rows");
        } catch (Exception e) {
            Log.w(TAG, "Frankfurter sync failed", e);
        }
    }

    /**
     * Enqueues the periodic sync. Safe to call on every app start — {@code KEEP} policy
     * leaves an existing schedule alone.
     */
    public static void schedule(@NonNull Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                PortfolioSyncWorker.class, INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req);
    }
}
