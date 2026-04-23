package com.my.finmon;

import android.app.Application;

import com.my.finmon.sync.PortfolioSyncWorker;

/**
 * App entry point. Warms {@link ServiceLocator} (and therefore the Room DB) before any
 * Activity is created, then enqueues the periodic sync worker.
 *
 * Registered in AndroidManifest.xml via {@code android:name=".FinMonApplication"}.
 */
public final class FinMonApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ServiceLocator.get(this);
        PortfolioSyncWorker.schedule(this);
    }
}
