package com.my.finmon.ui.filter;

import androidx.annotation.NonNull;

import java.time.LocalDate;

/**
 * Period axis of the global filter shared across Portfolio, Breakdown, Chart, and
 * (later) Bonds. {@link #CUSTOM} is special — its window is a user-picked
 * {@code from..to} range stored in {@link GlobalFilterViewModel} alongside the
 * selected period rather than derived from {@link #windowStart(LocalDate)}.
 */
public enum FilterPeriod {
    FIVE_DAYS,
    ONE_MONTH,
    SIX_MONTHS,
    YTD,
    ONE_YEAR,
    FIVE_YEARS,
    ALL_TIME,
    CUSTOM;

    /** Window start relative to {@code today} for non-custom periods. */
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
                throw new IllegalStateException(
                        "CUSTOM period has user-picked range — read it from GlobalFilterViewModel");
        }
    }
}
