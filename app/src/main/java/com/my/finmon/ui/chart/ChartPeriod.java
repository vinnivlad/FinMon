package com.my.finmon.ui.chart;

import androidx.annotation.NonNull;

import java.time.LocalDate;

/**
 * Quick-period options for the global Chart tab. {@link #CUSTOM} is special — its
 * window is a user-picked {@code from..to} range stored alongside the selected period
 * in the ViewModel rather than derived from {@link #windowStart(LocalDate)}.
 *
 * <p>Distinct from {@link com.my.finmon.ui.breakdown.Period} (4 values) — the chart
 * wants finer granularity (5d, 6m, 5y) and a custom range. State is independent.
 */
public enum ChartPeriod {
    FIVE_DAYS,
    ONE_MONTH,
    SIX_MONTHS,
    YTD,
    ONE_YEAR,
    FIVE_YEARS,
    ALL_TIME,
    CUSTOM;

    /**
     * Window start relative to {@code today} for non-custom periods. Throws for
     * {@link #CUSTOM} — callers should read the user-picked range from the ViewModel
     * rather than calling this.
     */
    @NonNull
    public LocalDate windowStart(@NonNull LocalDate today) {
        switch (this) {
            case FIVE_DAYS: return today.minusDays(5);
            case ONE_MONTH: return today.minusMonths(1);
            case SIX_MONTHS: return today.minusMonths(6);
            case YTD: return today.withDayOfYear(1);
            case ONE_YEAR: return today.minusYears(1);
            case FIVE_YEARS: return today.minusYears(5);
            case ALL_TIME: return LocalDate.of(1970, 1, 1);
            case CUSTOM:
            default:
                throw new IllegalStateException("CUSTOM period has user-picked range — read it from the ViewModel");
        }
    }
}
