package com.my.finmon.ui.breakdown;

import androidx.annotation.NonNull;

import java.time.LocalDate;

/**
 * Period filter for the currency-breakdown screen. Each value maps to a window
 * {@code [start, today]} used by {@code PortfolioRepository.getTradeRows}.
 *
 * <p>{@link #CUSTOM} is special — its window comes from a user-picked
 * {@code from..to} range stored alongside the period in
 * {@link CurrencyBreakdownViewModel}, so {@link #windowStart} throws for it.
 */
public enum Period {
    ONE_MONTH,
    SIX_MONTHS,
    YTD,
    ONE_YEAR,
    FIVE_YEARS,
    ALL_TIME,
    CUSTOM;

    /** Window start for this period relative to {@code today}. */
    @NonNull
    public LocalDate windowStart(@NonNull LocalDate today) {
        switch (this) {
            case ONE_MONTH: return today.minusMonths(1);
            case SIX_MONTHS: return today.minusMonths(6);
            case YTD: return today.withDayOfYear(1);
            case ONE_YEAR: return today.minusYears(1);
            case FIVE_YEARS: return today.minusYears(5);
            case ALL_TIME: return LocalDate.of(1970, 1, 1);
            case CUSTOM:
            default:
                throw new IllegalStateException(
                        "CUSTOM period has user-picked range — read it from the ViewModel");
        }
    }
}
