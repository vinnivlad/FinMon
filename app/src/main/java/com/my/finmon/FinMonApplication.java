package com.my.finmon;

import android.app.Application;

/**
 * App entry point. Warms {@link ServiceLocator} (and therefore the Room DB) before any
 * Activity is created, so the first screen never races the seed callback.
 *
 * Registered in AndroidManifest.xml via {@code android:name=".FinMonApplication"}.
 */
public final class FinMonApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ServiceLocator.get(this);
    }
}
