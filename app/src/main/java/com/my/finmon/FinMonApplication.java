package com.my.finmon;

import android.app.Application;

import com.my.finmon.data.FinMonDatabase;
import com.my.finmon.devtools.DevSeeder;
import com.my.finmon.sync.PortfolioSyncWorker;

/**
 * App entry point. Warms {@link ServiceLocator} (and therefore the Room DB) before any
 * Activity is created, then enqueues the periodic sync worker.
 *
 * <p>In DEBUG builds the DB is wiped on every launch and {@link DevSeeder} reseeds a
 * fresh set of assets + trades + one coupon, so the emulator always shows a meaningful
 * portfolio. Imports done during a session don't survive — that's intentional during
 * development.
 *
 * <p>Registered in AndroidManifest.xml via {@code android:name=".FinMonApplication"}.
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

        // In DEBUG, also fire the worker once on launch so Yahoo / Frankfurter wiring is
        // exercised immediately — DevSeeder leaves a 60-day gap at the right edge of the
        // stub data specifically so this run has work to do.
        if (BuildConfig.DEBUG) {
            PortfolioSyncWorker.runOnce(this);
        }
    }
}
