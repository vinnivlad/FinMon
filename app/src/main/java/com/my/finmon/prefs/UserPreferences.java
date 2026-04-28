package com.my.finmon.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.my.finmon.data.model.Currency;

/**
 * User-facing preferences. Distinct from internal app constants — the app's base
 * currency is fixed at USD ({@code PortfolioRepository.BASE_CURRENCY}); this class only
 * carries display-layer choices (UTC/timezone analogy: the app stores in USD always,
 * the user picks what to render as the headline number).
 *
 * Backed by {@link SharedPreferences}. Exposes both synchronous getters and a LiveData
 * so any screen can rebind on change without manual broadcast plumbing.
 */
public final class UserPreferences {

    private static final String FILE = "finmon_prefs";
    private static final String KEY_DISPLAY_CURRENCY = "display_currency";

    private final SharedPreferences prefs;
    private final MutableLiveData<Currency> displayCurrencyLive = new MutableLiveData<>();

    /** Listener kept as a field so it isn't GC'd — SharedPreferences holds it weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (sp, key) -> {
                if (KEY_DISPLAY_CURRENCY.equals(key)) {
                    displayCurrencyLive.postValue(getDisplayCurrency());
                }
            };

    public UserPreferences(@NonNull Context appContext) {
        this.prefs = appContext.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        this.displayCurrencyLive.setValue(getDisplayCurrency());
        this.prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    @NonNull
    public Currency getDisplayCurrency() {
        String raw = prefs.getString(KEY_DISPLAY_CURRENCY, Currency.USD.name());
        try {
            return Currency.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return Currency.USD;
        }
    }

    public void setDisplayCurrency(@NonNull Currency currency) {
        prefs.edit().putString(KEY_DISPLAY_CURRENCY, currency.name()).apply();
    }

    /** Updates whenever {@link #setDisplayCurrency} is called from anywhere in the process. */
    @NonNull
    public LiveData<Currency> displayCurrency() {
        return displayCurrencyLive;
    }
}
