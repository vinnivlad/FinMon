package com.my.finmon.ui.breakdown;

import androidx.annotation.NonNull;

import com.my.finmon.data.model.Currency;

import java.math.BigDecimal;

/**
 * One row on the per-currency breakdown screen. Same math as
 * {@code PortfolioRepository.NativeBucket}, plus the currency key — the VM flattens
 * the map into a list of these and the adapter renders them. No FX crossing.
 */
public final class CurrencyBucket {
    @NonNull public final Currency currency;
    @NonNull public final BigDecimal value;
    @NonNull public final BigDecimal invested;
    @NonNull public final BigDecimal pnl;

    public CurrencyBucket(
            @NonNull Currency currency,
            @NonNull BigDecimal value,
            @NonNull BigDecimal invested,
            @NonNull BigDecimal pnl) {
        this.currency = currency;
        this.value = value;
        this.invested = invested;
        this.pnl = pnl;
    }
}
