package com.my.finmon.ui.market;

import androidx.annotation.NonNull;

/**
 * Period options for the Market browser tab. Each maps to a Yahoo (interval, range)
 * pair the {@code MarketDataRepository.fetchSeriesByRange} call uses directly.
 *
 * <p>{@link #ONE_DAY} adds intraday granularity (5-minute bars) so today's price action
 * is meaningful in the chart — that's the use case the Market tab is mostly for, and
 * it's why this enum exists separately from {@link com.my.finmon.ui.chart.ChartPeriod}.
 *
 * <p>{@link #CUSTOM} pairs with a user-picked {@code from..to} stored on the ViewModel.
 */
public enum MarketPeriod {
    ONE_DAY("5m", "1d"),
    FIVE_DAYS("15m", "5d"),
    ONE_MONTH("1d", "1mo"),
    SIX_MONTHS("1d", "6mo"),
    YTD("1d", "ytd"),
    ONE_YEAR("1d", "1y"),
    FIVE_YEARS("1wk", "5y"),
    ALL_TIME("1mo", "max"),
    CUSTOM("1d", "");  // range unused; window driven by user-picked dates

    @NonNull public final String interval;
    @NonNull public final String range;

    MarketPeriod(@NonNull String interval, @NonNull String range) {
        this.interval = interval;
        this.range = range;
    }
}
