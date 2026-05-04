package com.my.finmon.security;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import androidx.annotation.NonNull;

/**
 * Process-wide unlock state for the app-lock screen.
 *
 * <p>Lives at the process level (not the activity) so it survives configuration-change
 * recreations — rotation, theme switch, locale change all destroy and recreate
 * MainActivity, but the user is still in the app and shouldn't be re-prompted.
 *
 * <p>{@link ProcessLifecycleOwner} fires {@code onStop} only when *all* activities in
 * the process have stopped — it has an internal debounce that swallows the brief
 * activity-only tear-and-rebuild that happens during a config change. That's exactly
 * the signal we need: real backgrounding clears the flag, config-change recreations
 * don't.
 *
 * <p>Process death also resets the flag (the static is reinitialised when the process
 * restarts), so a kill-and-relaunch correctly re-prompts.
 */
public final class AppLockState {

    private static volatile boolean unlocked = false;
    private static boolean observerInstalled = false;

    private AppLockState() { /* no instances */ }

    /**
     * Idempotent — call once from {@link android.app.Application#onCreate}. Wires
     * a process-level observer that clears the unlock flag on real backgrounding.
     */
    public static void install() {
        if (observerInstalled) return;
        observerInstalled = true;
        ProcessLifecycleOwner.get().getLifecycle().addObserver(
                new DefaultLifecycleObserver() {
                    @Override
                    public void onStop(@NonNull LifecycleOwner owner) {
                        unlocked = false;
                    }
                });
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void markUnlocked() {
        unlocked = true;
    }
}
