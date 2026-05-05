package com.my.finmon.ui.settings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.model.Currency;
import com.my.finmon.prefs.ThemeMode;
import com.my.finmon.prefs.UserPreferences;

import java.math.BigDecimal;

/**
 * Backs {@link SettingsFragment}. Wraps {@link UserPreferences} so the fragment can
 * observe display + tax-default state reactively and write through a single API.
 */
public final class SettingsViewModel extends ViewModel {

    private final UserPreferences prefs;

    public SettingsViewModel(@NonNull UserPreferences prefs) {
        this.prefs = prefs;
    }

    @NonNull
    public LiveData<Currency> displayCurrency() {
        return prefs.displayCurrency();
    }

    public void setDisplayCurrency(@NonNull Currency currency) {
        prefs.setDisplayCurrency(currency);
    }

    @NonNull
    public LiveData<BigDecimal> defaultStockTaxPct() {
        return prefs.defaultStockTaxPct();
    }

    @NonNull
    public LiveData<BigDecimal> defaultBondTaxPct() {
        return prefs.defaultBondTaxPct();
    }

    public void setDefaultStockTaxPct(@NonNull BigDecimal pct) {
        prefs.setDefaultStockTaxPct(pct);
    }

    public void setDefaultBondTaxPct(@NonNull BigDecimal pct) {
        prefs.setDefaultBondTaxPct(pct);
    }

    @NonNull
    public LiveData<ThemeMode> themeMode() {
        return prefs.themeMode();
    }

    /**
     * Persists the choice and applies it process-wide. {@link AppCompatDelegate} recreates
     * any visible activity so the new theme inflates immediately — that's the expected
     * UX, no extra wiring needed on caller side.
     */
    public void setThemeMode(@NonNull ThemeMode mode) {
        prefs.setThemeMode(mode);
        AppCompatDelegate.setDefaultNightMode(mode.delegateMode());
    }

    @NonNull
    public LiveData<Boolean> notificationsEnabled() {
        return prefs.notificationsEnabled();
    }

    public boolean isNotificationsEnabled() {
        return prefs.isNotificationsEnabled();
    }

    public void setNotificationsEnabled(boolean enabled) {
        prefs.setNotificationsEnabled(enabled);
    }

    @NonNull
    public static ViewModelProvider.Factory factory(@NonNull Context anyContext) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(SettingsViewModel.class)) {
                    return (T) new SettingsViewModel(sl.userPreferences());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
