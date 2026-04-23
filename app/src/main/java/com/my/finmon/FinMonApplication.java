package com.my.finmon;

import android.app.Application;

import com.my.finmon.data.FinMonDatabase;
import com.my.finmon.devtools.DevSeeder;
import com.my.finmon.sync.PortfolioSyncWorker;

/**
 * App entry point. Warms {@link ServiceLocator} (and therefore the Room DB) before any
 * Activity is created, then enqueues the periodic sync worker.
 *
 * In DEBUG builds the DB is wiped on every launch and {@link DevSeeder} reseeds a fresh
 * set of assets + trades + one coupon, so the emulator always shows a meaningful
 * portfolio. The wipe runs <em>before</em> {@link ServiceLocator#get} so Room opens a
 * clean file and re-fires {@code SEED_CALLBACK.onCreate}.
 *
 * Registered in AndroidManifest.xml via {@code android:name=".FinMonApplication"}.
 */
public final class FinMonApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            deleteDatabase(FinMonDatabase.DB_NAME);
        }

        ServiceLocator sl = ServiceLocator.get(this);

        if (BuildConfig.DEBUG) {
            DevSeeder.seed(sl);
        }

        PortfolioSyncWorker.schedule(this);
    }
}
