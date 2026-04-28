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

        public Status(
                @NonNull Stage stage, int currentItem, int totalItems,
                @NonNull String label, @Nullable String errorMessage) {
            this.stage = stage;
            this.currentItem = currentItem;
            this.totalItems = totalItems;
            this.label = label;
            this.errorMessage = errorMessage;
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

    /** Re-runs sync after a failure. Allowed only from the {@code FAILED} state. */
    public void retry() {
        Status current = status.getValue();
        if (current == null || current.stage != Stage.FAILED) return;
        if (!running.compareAndSet(false, true)) return;
        kickOff();
    }

    /**
     * User chose "continue with stale data" after a failure. Marks status as DONE so
     * the UI's overlay observer hides the overlay.
     */
    public void dismissAfterFailure() {
        Status current = status.getValue();
        if (current == null || current.stage != Stage.FAILED) return;
        status.postValue(new Status(Stage.DONE, 0, 0, "", null));
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
            } catch (Exception e) {
                Log.w(TAG, "startup sync failed", e);
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                status.postValue(new Status(Stage.FAILED, 0, 0, "", msg));
            } finally {
                running.set(false);
            }
        });
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
