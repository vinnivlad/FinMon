package com.my.finmon.ui.breakdown;

import androidx.annotation.NonNull;

import java.time.LocalDate;

/**
 * Period filter for the currency-breakdown screen. Each value maps to a window
 * {@code [start, today]} used by {@code PortfolioRepository.getTradeRows}. The chart
 * screen will eventually share this enum but keep independent selection state.
 */
public enum Period {
    ALL_TIME,
    YTD,
    ONE_MONTH,
    ONE_YEAR;

    /** Window start for this period relative to {@code today}. */
    @NonNull
    public LocalDate windowStart(@NonNull LocalDate today) {
        switch (this) {
            case YTD: return today.withDayOfYear(1);
            case ONE_MONTH: return today.minusMonths(1);
            case ONE_YEAR: return today.minusYears(1);
            case ALL_TIME:
            default:
                // Pre-dates any realistic personal-portfolio event, so every lot is "in window".
                return LocalDate.of(1970, 1, 1);
        }
    }
}
