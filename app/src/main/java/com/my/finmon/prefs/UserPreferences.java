package com.my.finmon.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.TaxRates;

import java.math.BigDecimal;

/**
 * User-facing preferences. Distinct from internal app constants — the app's base
 * currency is fixed at USD ({@code PortfolioRepository.BASE_CURRENCY}); this class only
 * carries display-layer choices (UTC/timezone analogy: the app stores in USD always,
 * the user picks what to render as the headline number).
 *
 * Backed by {@link SharedPreferences}. Exposes both synchronous getters and a LiveData
 * so any screen can rebind on change without manual broadcast plumbing.
 */
public final class UserPreferences implements TaxRates {

    private static final String FILE = "finmon_prefs";
    private static final String KEY_DISPLAY_CURRENCY = "display_currency";
    private static final String KEY_DEFAULT_STOCK_TAX_PCT = "default_stock_tax_pct";
    private static final String KEY_DEFAULT_BOND_TAX_PCT = "default_bond_tax_pct";
    private static final String KEY_THEME_MODE = "theme_mode";

    /** Ukrainian PIT on stock dividends and capital gains, applied at auto-ingest. */
    private static final float DEFAULT_STOCK_TAX_PCT = 15f;
    /** UAH OVDP coupons + capital gains are tax-exempt by Ukrainian law. */
    private static final float DEFAULT_BOND_TAX_PCT = 0f;
    private static final ThemeMode DEFAULT_THEME_MODE = ThemeMode.SYSTEM;

    private final SharedPreferences prefs;
    private final MutableLiveData<Currency> displayCurrencyLive = new MutableLiveData<>();
    private final MutableLiveData<BigDecimal> defaultStockTaxPctLive = new MutableLiveData<>();
    private final MutableLiveData<BigDecimal> defaultBondTaxPctLive = new MutableLiveData<>();
    private final MutableLiveData<ThemeMode> themeModeLive = new MutableLiveData<>();

    /** Listener kept as a field so it isn't GC'd — SharedPreferences holds it weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (sp, key) -> {
                if (KEY_DISPLAY_CURRENCY.equals(key)) {
                    displayCurrencyLive.postValue(getDisplayCurrency());
                } else if (KEY_DEFAULT_STOCK_TAX_PCT.equals(key)) {
                    defaultStockTaxPctLive.postValue(getDefaultStockTaxPct());
                } else if (KEY_DEFAULT_BOND_TAX_PCT.equals(key)) {
                    defaultBondTaxPctLive.postValue(getDefaultBondTaxPct());
                } else if (KEY_THEME_MODE.equals(key)) {
                    themeModeLive.postValue(getThemeMode());
                }
            };

    public UserPreferences(@NonNull Context appContext) {
        this.prefs = appContext.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        this.displayCurrencyLive.setValue(getDisplayCurrency());
        this.defaultStockTaxPctLive.setValue(getDefaultStockTaxPct());
        this.defaultBondTaxPctLive.setValue(getDefaultBondTaxPct());
        this.themeModeLive.setValue(getThemeMode());
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

    @NonNull
    public BigDecimal getDefaultStockTaxPct() {
        return BigDecimal.valueOf(prefs.getFloat(KEY_DEFAULT_STOCK_TAX_PCT, DEFAULT_STOCK_TAX_PCT));
    }

    @NonNull
    public BigDecimal getDefaultBondTaxPct() {
        return BigDecimal.valueOf(prefs.getFloat(KEY_DEFAULT_BOND_TAX_PCT, DEFAULT_BOND_TAX_PCT));
    }

    public void setDefaultStockTaxPct(@NonNull BigDecimal pct) {
        prefs.edit().putFloat(KEY_DEFAULT_STOCK_TAX_PCT, pct.floatValue()).apply();
    }

    public void setDefaultBondTaxPct(@NonNull BigDecimal pct) {
        prefs.edit().putFloat(KEY_DEFAULT_BOND_TAX_PCT, pct.floatValue()).apply();
    }

    @NonNull
    public LiveData<BigDecimal> defaultStockTaxPct() {
        return defaultStockTaxPctLive;
    }

    @NonNull
    public LiveData<BigDecimal> defaultBondTaxPct() {
        return defaultBondTaxPctLive;
    }

    @NonNull
    public ThemeMode getThemeMode() {
        String raw = prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE.name());
        return ThemeMode.parseOr(raw, DEFAULT_THEME_MODE);
    }

    public void setThemeMode(@NonNull ThemeMode mode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name()).apply();
    }

    @NonNull
    public LiveData<ThemeMode> themeMode() {
        return themeModeLive;
    }

    @NonNull
    @Override
    public BigDecimal defaultRate(@NonNull AssetType type) {
        switch (type) {
            case STOCK: return getDefaultStockTaxPct();
            case BOND: return getDefaultBondTaxPct();
            default: return BigDecimal.ZERO;
        }
    }
}
