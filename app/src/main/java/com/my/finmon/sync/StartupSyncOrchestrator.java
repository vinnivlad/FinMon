package com.my.finmon.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.my.finmon.ServiceLocator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground sync runner — drives the same {@link SyncEngine} stages the periodic
 * worker uses, but emits per-stage status to a {@link LiveData} so the UI can show a
 * blocking spinner with progress.
 *
 * <p>Singleton-scoped (one per process) and held by {@link ServiceLocator}. The first
 * activity that observes its status drives the startup overlay. On rotation the
 * activity re-attaches to the same orchestrator — no re-run, no double-fetch.
 */
public final class StartupSyncOrchestrator {

    private static final String TAG = "StartupSyncOrch";

    public enum Stage {
        IDLE,
        STARTING,
        IMPORTING,
        STOCK_PRICES,
        FX,
        BOND_COUPONS,
        SNAPSHOTS,
        DONE,
        FAILED
    }

    /**
     * UI-facing status. {@code currentItem}/{@code totalItems} are 0/0 for stages that
     * don't have a meaningful per-item dimension (e.g. FX is a single bulk fetch).
     */
    public static final class Status {
        @NonNull public final Stage stage;
        public final int currentItem;
        public final int totalItems;
        @NonNull public final String label;
        @Nullable public final String errorMessage;
        /** Stage that originated the failure — non-null only when {@code stage == FAILED}
         *  AND the failure was a structured per-stage one (vs. a network-down sentinel). */
        @Nullable public final Stage failedStage;

        public Status(
                @NonNull Stage stage, int currentItem, int totalItems,
                @NonNull String label, @Nullable String errorMessage) {
            this(stage, currentItem, totalItems, label, errorMessage, null);
        }

        public Status(
                @NonNull Stage stage, int currentItem, int totalItems,
                @NonNull String label, @Nullable String errorMessage,
                @Nullable Stage failedStage) {
            this.stage = stage;
            this.currentItem = currentItem;
            this.totalItems = totalItems;
            this.label = label;
            this.errorMessage = errorMessage;
            this.failedStage = failedStage;
        }

        public static Status idle() {
            return new Status(Stage.IDLE, 0, 0, "", null);
        }
    }

    /** Sentinel error message — recognised by the UI to render the no-internet copy. */
    public static final String ERROR_NO_INTERNET = "NO_INTERNET";

    private final ServiceLocator sl;
    private final Context appContext;
    private final ExecutorService syncExecutor;
    private final MutableLiveData<Status> status = new MutableLiveData<>(Status.idle());
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Set by {@link #runImport}; consulted by {@link #retry} so a Retry after a failed
     *  import re-imports the same JSON instead of running the startup-sync path. */
    @Nullable private volatile String pendingImportJson;
    /** One-shot signal: the most recent run was a successful import. Consumed by the UI
     *  via {@link #consumeImportJustFinished} so it can route to the Portfolio screen. */
    private final AtomicBoolean importJustFinished = new AtomicBoolean(false);

    public StartupSyncOrchestrator(@NonNull ServiceLocator sl, @NonNull Context appContext) {
        this.sl = sl;
        this.appContext = appContext.getApplicationContext();
        // Dedicated single-thread executor — cannot reuse ioExecutor because SyncEngine
        // calls into PortfolioRepository which submits to ioExecutor and blocks on
        // Future.get(); reusing the same thread would deadlock.
        this.syncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "finmon-startup-sync");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    @NonNull
    public LiveData<Status> status() { return status; }

    /**
     * Kick off sync. Idempotent within a single process — if a run is in-flight or has
     * already completed, this is a no-op. Use {@link #retry()} after a {@code FAILED}
     * to re-run.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Status current = status.getValue();
        if (current != null && current.stage == Stage.DONE) {
            running.set(false);
            return;
        }
        kickOff();
    }

    /** Re-runs sync after a failure. Allowed only from the {@code FAILED} state.
     *  Drops the NBU bond catalog cache so a stale-cache failure isn't re-served on
     *  retry — a fresh fetch happens on the next {@code findBondByIsin} call. */
    public void retry() {
        Status current = status.getValue();
        if (current == null || current.stage != Stage.FAILED) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            sl.marketDataRepository().dropBondCatalogCache().get();
        } catch (Exception ignored) {
            // Best-effort. Stale cache may still be served; user can retry again.
        }
        if (pendingImportJson != null) {
            kickOffImport();
        } else {
            kickOff();
        }
    }

    /**
     * User chose "continue with stale data" after a failure. Marks status as DONE so
     * the UI's overlay observer hides the overlay.
     */
    /**
     * One-shot read of the "import just succeeded" signal. Returns {@code true} at most
     * once per successful import; the UI uses this to navigate to Portfolio without
     * re-firing on rotation.
     */
    public boolean consumeImportJustFinished() {
        return importJustFinished.compareAndSet(true, false);
    }

    public void dismissAfterFailure() {
        Status current = status.getValue();
        if (current == null || current.stage != Stage.FAILED) return;
        pendingImportJson = null;  // user chose to abandon a failed import
        status.postValue(new Status(Stage.DONE, 0, 0, "", null));
    }

    /**
     * Drives a JSON import end-to-end: blocks the UI via the same overlay the startup
     * sync uses, runs the import (DB wipe + restore + remote enrichment), then
     * regenerates portfolio snapshots so the chart reflects the imported history
     * immediately. Idempotent — concurrent calls are dropped.
     */
    public void runImport(@NonNull String json) {
        if (!running.compareAndSet(false, true)) return;
        pendingImportJson = json;
        kickOffImport();
    }

    private void kickOffImport() {
        status.postValue(new Status(Stage.IMPORTING, 0, 0, "", null));
        syncExecutor.execute(() -> {
            try {
                String json = pendingImportJson;
                if (json == null) {
                    throw new IllegalStateException("runImport called without pending JSON");
                }
                // Phase A: import (wipe + restore + Yahoo/NBU/Frankfurter enrichment).
                sl.importExportRepository().importFromJson(json).get();
                // Phase B: snapshots — emit per-day progress through the same callback the
                // startup sync uses, so the overlay shows a moving counter for the (often
                // large) historical rebuild after a full re-import.
                java.time.LocalDate yesterday = java.time.LocalDate.now().minusDays(1);
                SyncEngine.syncPortfolioSnapshots(sl, yesterday, this::emit);
                pendingImportJson = null;
                importJustFinished.set(true);
                status.postValue(new Status(Stage.DONE, 0, 0, "", null));
            } catch (SyncEngine.StageFailedException sfe) {
                Log.w(TAG, "import sync stage " + sfe.stage + " failed: " + sfe.getMessage());
                status.postValue(new Status(
                        Stage.FAILED, 0, 0, "", sfe.getMessage(), mapEngineStage(sfe.stage)));
            } catch (Exception e) {
                Log.w(TAG, "import sync failed", e);
                // ExecutionException from Future.get() wraps the underlying throwable;
                // unwrap so a StageFailedException raised inside enrichAfterImport still
                // routes to the correct Step: <stage> failure copy.
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof SyncEngine.StageFailedException) {
                    SyncEngine.StageFailedException sfe = (SyncEngine.StageFailedException) cause;
                    status.postValue(new Status(
                            Stage.FAILED, 0, 0, "", sfe.getMessage(), mapEngineStage(sfe.stage)));
                } else {
                    String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                    status.postValue(new Status(Stage.FAILED, 0, 0, "", msg));
                }
            } finally {
                running.set(false);
            }
        });
    }

    private void kickOff() {
        status.postValue(new Status(Stage.STARTING, 0, 0, "", null));
        syncExecutor.execute(() -> {
            try {
                // Up-front connectivity check. SyncEngine swallows individual fetch
                // failures (correct for partial-outage cases) but the snapshot stage
                // is local and would still succeed, masking a total network outage as
                // a clean DONE. Fail loudly here so the user is offered Retry.
                if (!hasNetwork()) {
                    status.postValue(new Status(Stage.FAILED, 0, 0, "", ERROR_NO_INTERNET));
                    return;
                }
                SyncEngine.runAll(sl, this::emit);
                status.postValue(new Status(Stage.DONE, 0, 0, "", null));
            } catch (SyncEngine.StageFailedException sfe) {
                Log.w(TAG, "stage " + sfe.stage + " failed: " + sfe.getMessage());
                Stage uiStage = mapEngineStage(sfe.stage);
                status.postValue(new Status(
                        Stage.FAILED, 0, 0, "", sfe.getMessage(), uiStage));
            } catch (Exception e) {
                Log.w(TAG, "startup sync failed", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                status.postValue(new Status(Stage.FAILED, 0, 0, "", msg));
            } finally {
                running.set(false);
            }
        });
    }

    private static Stage mapEngineStage(@NonNull SyncEngine.Stage es) {
        switch (es) {
            case STOCK_PRICES: return Stage.STOCK_PRICES;
            case FX:           return Stage.FX;
            case BOND_COUPONS: return Stage.BOND_COUPONS;
            case SNAPSHOTS:    return Stage.SNAPSHOTS;
            default:           return Stage.STARTING;
        }
    }

    private boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return false;
        // Internet capability is the load-bearing check; validation flag is best-effort
        // (some networks don't advertise it correctly, so we don't require it).
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void emit(@NonNull SyncEngine.Stage engineStage, int current, int total, @NonNull String label) {
        Stage uiStage;
        switch (engineStage) {
            case STOCK_PRICES: uiStage = Stage.STOCK_PRICES; break;
            case FX:           uiStage = Stage.FX; break;
            case BOND_COUPONS: uiStage = Stage.BOND_COUPONS; break;
            case SNAPSHOTS:    uiStage = Stage.SNAPSHOTS; break;
            default:           uiStage = Stage.STARTING; break;
        }
        status.postValue(new Status(uiStage, current, total, label, null));
    }
}
