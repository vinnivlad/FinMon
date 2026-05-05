package com.my.finmon;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.my.finmon.data.FinMonDatabase;
import com.my.finmon.devtools.DevSeeder;
import com.my.finmon.notifications.NotificationHelper;
import com.my.finmon.notifications.NotificationScheduler;
import com.my.finmon.security.AppLockState;
import com.my.finmon.sync.PortfolioSyncWorker;

/**
 * App entry point. Warms {@link ServiceLocator} (and therefore the Room DB) before any
 * Activity is created, kicks off the foreground startup sync, and registers the periodic
 * background sync.
 *
 * <p>The DEBUG wipe-and-seed path is gated by {@link #WIPE_AND_SEED_ON_DEBUG_LAUNCH}.
 * When {@code true} (default during fixture-driven dev), the DB is wiped on every launch
 * and {@link DevSeeder} reseeds a fresh set of assets + trades + one coupon — imports
 * done during a session don't survive the next launch. Flip to {@code false} when
 * working with real imported data so it persists across runs.
 *
 * <p>Registered in AndroidManifest.xml via {@code android:name=".FinMonApplication"}.
 */
public final class FinMonApplication extends Application {

    /**
     * Flip to {@code true} to restore the DEBUG fixture-driven flow (wipe DB +
     * {@link DevSeeder} on every launch). Currently {@code false} so real imports
     * persist across launches.
     */
    private static final boolean WIPE_AND_SEED_ON_DEBUG_LAUNCH = false;

    @Override
    public void onCreate() {
        super.onCreate();

        boolean wipeAndSeed = BuildConfig.DEBUG && WIPE_AND_SEED_ON_DEBUG_LAUNCH;

        if (wipeAndSeed) {
            deleteDatabase(FinMonDatabase.DB_NAME);
        }

        ServiceLocator sl = ServiceLocator.get(this);

        // Apply the user's theme choice before any Activity inflates so the right
        // light/dark variant is picked from frame 0. Must run before super-class
        // continues bringing up activities (we're in onCreate already; this just
        // races the first onCreate of MainActivity).
        AppCompatDelegate.setDefaultNightMode(
                sl.userPreferences().getThemeMode().delegateMode());

        // Process-level unlock state — persists across activity recreations (theme
        // switch, rotation, locale change) and resets only on real backgrounding.
        AppLockState.install();

        // Periodic background sync — separate from the foreground startup sync below.
        PortfolioSyncWorker.schedule(this);

        // Notifications: register the channel once, then sync the scheduled workers
        // with the persisted master toggle. Cold start always runs through here so
        // post-reinstall or post-prefs-clear states converge automatically.
        NotificationHelper.ensureChannel(this);
        NotificationScheduler.apply(this, sl.userPreferences().isNotificationsEnabled());

        if (wipeAndSeed) {
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
