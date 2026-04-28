package com.my.finmon;

import android.app.Application;

import com.my.finmon.data.FinMonDatabase;
import com.my.finmon.devtools.DevSeeder;
import com.my.finmon.sync.PortfolioSyncWorker;

/**
 * App entry point. Warms {@link ServiceLocator} (and therefore the Room DB) before any
 * Activity is created, kicks off the foreground startup sync, and registers the periodic
 * background sync.
 *
 * <p>In DEBUG builds the DB is wiped on every launch and {@link DevSeeder} reseeds a
 * fresh set of assets + trades + one coupon, so the emulator always shows a meaningful
 * portfolio. The seeder runs <em>before</em> the startup sync orchestrator on a shared
 * background thread — the orchestrator would otherwise race against an empty DB on the
 * first launch after a wipe. Imports done during a session don't survive the next
 * launch — that's intentional during development.
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

        // Periodic background sync — separate from the foreground startup sync below.
        PortfolioSyncWorker.schedule(this);

        if (BuildConfig.DEBUG) {
            // Seed first, then start sync. Both run on the view executor (single thread,
            // serialized) so the orchestrator can't race ahead of the seeder's inserts.
            sl.viewExecutor().execute(() -> {
                DevSeeder.seedSync(sl);
                sl.startupSyncOrchestrator().start();
            });
        } else {
            sl.startupSyncOrchestrator().start();
        }
    }
}
