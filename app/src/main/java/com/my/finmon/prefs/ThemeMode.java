package com.my.finmon.prefs;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * User-selectable theme mode. Maps to {@link AppCompatDelegate}'s night-mode constants
 * so flipping the choice triggers an activity recreate into the right theme.
 */
public enum ThemeMode {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES);

    private final int delegateMode;

    ThemeMode(int delegateMode) {
        this.delegateMode = delegateMode;
    }

    public int delegateMode() {
        return delegateMode;
    }

    @NonNull
    public static ThemeMode parseOr(@NonNull String name, @NonNull ThemeMode fallback) {
        try {
            return ThemeMode.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
