package com.my.finmon.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.my.finmon.ServiceLocator;

import java.util.concurrent.TimeUnit;

/**
 * Periodic background refresh — keeps {@code stock_price}, {@code exchange_rate}, bond
 * coupons, and {@code portfolio_value} snapshots current. Same logic the foreground
 * {@link StartupSyncOrchestrator} runs; both delegate to {@link SyncEngine}.
 *
 * <p>Per-item failures are caught inside the engine; only structural exceptions reach
 * here and turn into {@code Result.retry()}.
 */
public final class PortfolioSyncWorker extends Worker {

    private static final String TAG = "PortfolioSyncWorker";
    private static final String UNIQUE_NAME = "finmon_sync";
    private static final long INTERVAL_HOURS = 12;

    public PortfolioSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        try {
            SyncEngine.runAll(sl, SyncEngine.ProgressCallback.NO_OP);
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "sync aborted", e);
            return Result.retry();
        }
    }

    /**
     * Enqueues the periodic sync. Called from {@code FinMonApplication.onCreate} on every
     * app start.
     *
     * <p><b>Initial delay = interval</b> so the periodic worker never fires on cold
     * launch — that case is owned by {@link StartupSyncOrchestrator}, which runs the
     * same {@link SyncEngine} stages with UI progress. Without the initial delay, the
     * worker would race the orchestrator on first install and produce duplicate Yahoo /
     * Frankfurter calls before either's writes were visible to the other.
     *
     * <p><b>{@code REPLACE} policy</b> resets the next-fire time on every launch. As
     * long as the user opens the app at least once per {@code INTERVAL_HOURS}, the
     * worker effectively never fires — the orchestrator handles each app-open sync.
     * The worker fires only when the user has been away for ≥{@code INTERVAL_HOURS},
     * which is exactly the case the orchestrator can't cover.
     */
    public static void schedule(@NonNull Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                PortfolioSyncWorker.class, INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS)
                .build();

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                req);
    }

    /**
     * Fires the worker once, immediately (subject to network constraint). Kept for
     * ad-hoc testing — production app-open sync now goes through
     * {@link StartupSyncOrchestrator}, not this method.
     */
    public static void runOnce(@NonNull Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(PortfolioSyncWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(ctx).enqueue(req);
    }
}
