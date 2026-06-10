package com.my.finmon.ui.charts;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.ConvertedSnapshot;
import com.my.finmon.data.repository.PortfolioRepository.NativeBucket;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.prefs.UserPreferences;
import com.my.finmon.ui.filter.FilterPeriod;
import com.my.finmon.ui.filter.GlobalFilterViewModel;
import com.my.finmon.ui.filter.GlobalFilterViewModel.CustomRange;
import com.my.finmon.util.PortfolioReturnSeries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Backs the Charts → Value page. Plots portfolio value and invested-capital
 * lines over the global filter's window. Reads filter axes (currency / period /
 * customRange) from the Activity-scoped {@link GlobalFilterViewModel}.
 */
public final class ValueChartViewModel extends ViewModel {

    private static final String TAG = "ValueChartVM";

    private final PortfolioRepository repo;
    private final UserPreferences prefs;
    private final GlobalFilterViewModel filter;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<ChartData> data = new MutableLiveData<>();

    /**
     * Re-render when the user changes the display currency in Settings, but only when
     * the active filter is "All" (the FX-converted view). For a specific-currency
     * filter the chart is FX-free and Settings has no effect on it.
     *
     * <p>Initialized in the constructor (not as a field initializer) because it
     * reads {@code this.filter}, which is itself only assigned in the constructor.
     */
    private final Observer<Currency> displayCurrencyObserver;

    /** Any global-filter axis change → re-fetch the series. */
    private final Observer<Currency> filterCurrencyObserver = c -> refresh();
    private final Observer<FilterPeriod> filterPeriodObserver = p -> refresh();
    private final Observer<CustomRange> filterCustomRangeObserver = r -> refresh();

    public ValueChartViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull UserPreferences prefs,
            @NonNull GlobalFilterViewModel filter,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.prefs = prefs;
        this.filter = filter;
        this.viewExecutor = viewExecutor;
        this.displayCurrencyObserver = c -> {
            if (filter.selectedCurrency().getValue() == null) refresh();
        };
        prefs.displayCurrency().observeForever(displayCurrencyObserver);
        filter.selectedCurrency().observeForever(filterCurrencyObserver);
        filter.selectedPeriod().observeForever(filterPeriodObserver);
        filter.customRange().observeForever(filterCustomRangeObserver);
    }

    @Override
    protected void onCleared() {
        prefs.displayCurrency().removeObserver(displayCurrencyObserver);
        filter.selectedCurrency().removeObserver(filterCurrencyObserver);
        filter.selectedPeriod().removeObserver(filterPeriodObserver);
        filter.customRange().removeObserver(filterCustomRangeObserver);
        super.onCleared();
    }

    @NonNull public LiveData<ChartData> data() { return data; }

    public void refresh() {
        Currency currency = filter.selectedCurrency().getValue();
        FilterPeriod period = filter.selectedPeriod().getValue();
        CustomRange custom = filter.customRange().getValue();

        viewExecutor.execute(() -> {
            try {
                LocalDate today = LocalDate.now();
                LocalDate from;
                LocalDate to;
                if (period == FilterPeriod.CUSTOM && custom != null) {
                    from = custom.from;
                    // Cap "to" at today — if the user picks a future date, only history
                    // up to today exists. The right-edge live point still appends.
                    to = custom.to.isAfter(today) ? today : custom.to;
                } else {
                    FilterPeriod p = period != null ? period : FilterPeriod.ALL_TIME;
                    from = p == FilterPeriod.CUSTOM ? today.minusYears(1) : p.windowStart(today);
                    to = today;
                }

                LocalDate yesterday = today.minusDays(1);
                LocalDate snapTo = to.isAfter(yesterday) ? yesterday : to;
                boolean includeToday = !to.isBefore(today);

                List<Point> points = new ArrayList<>();
                boolean anyGaps = false;

                if (currency == null) {
                    // "All" — FX-converted view in user's display currency.
                    Currency display = prefs.getDisplayCurrency();
                    List<ConvertedSnapshot> snapshots = (snapTo.isBefore(from))
                            ? new ArrayList<>()
                            : repo.getSnapshotsInDisplay(from, snapTo, display).get();
                    for (ConvertedSnapshot s : snapshots) {
                        points.add(new Point(s.date, s.value, s.invested, s.hasFxGaps));
                        if (s.hasFxGaps) anyGaps = true;
                    }
                    PortfolioTotals todayTotals = null;
                    if (includeToday) {
                        todayTotals = repo.getPortfolioTotals(today).get();
                        BigDecimal v = todayTotals.valueByDisplayCurrency.get(display);
                        BigDecimal i = todayTotals.investedByDisplayCurrency.get(display);
                        boolean todayGap = todayTotals.hasFxGaps || v == null || i == null;
                        if (v == null) v = todayTotals.valueInBase;
                        if (i == null) i = todayTotals.investedInBase;
                        points.add(new Point(today, v, i, todayGap));
                        if (todayGap) anyGaps = true;
                    }
                    // Cross-currency ribbon: the period-end value expressed in the other
                    // currencies (same source the Portfolio totals card uses). Computed at
                    // the last rendered point's date so it matches the headline; reuse
                    // today's totals when that point is today.
                    List<Equivalent> equivalents = null;
                    if (!points.isEmpty()) {
                        LocalDate endDate = points.get(points.size() - 1).date;
                        PortfolioTotals endTotals = (todayTotals != null && endDate.equals(today))
                                ? todayTotals
                                : repo.getPortfolioTotals(endDate).get();
                        equivalents = buildEquivalents(endTotals.valueByDisplayCurrency, display);
                    }
                    data.postValue(new ChartData(display, period, custom, points, anyGaps, equivalents));
                } else {
                    // Native single-currency view — no FX, no gaps.
                    List<ConvertedSnapshot> snapshots = (snapTo.isBefore(from))
                            ? new ArrayList<>()
                            : repo.getSnapshotsForCurrency(from, snapTo, currency).get();
                    for (ConvertedSnapshot s : snapshots) {
                        points.add(new Point(s.date, s.value, s.invested, false));
                    }
                    if (includeToday) {
                        PortfolioTotals todayTotals = repo.getPortfolioTotals(today).get();
                        NativeBucket bucket = todayTotals.bucketByCurrency.get(currency);
                        BigDecimal v = bucket != null ? bucket.value : BigDecimal.ZERO;
                        BigDecimal i = bucket != null ? bucket.invested : BigDecimal.ZERO;
                        points.add(new Point(today, v, i, false));
                    }
                    // Specific-currency mode is FX-free — no cross-currency ribbon.
                    data.postValue(new ChartData(currency, period, custom, points, false, null));
                }
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
            }
        });
    }

    /**
     * Same total expressed in each Currency other than {@code primary}, ordered by
     * Currency declaration (USD, EUR, UAH) for stable layout. Mirrors
     * {@code PortfolioViewModel.buildRibbon}.
     */
    @NonNull
    private static List<Equivalent> buildEquivalents(
            @NonNull java.util.Map<Currency, BigDecimal> valueByDisplayCurrency,
            @NonNull Currency primary) {
        List<Equivalent> out = new ArrayList<>();
        for (Currency c : Currency.values()) {
            if (c == primary) continue;
            BigDecimal v = valueByDisplayCurrency.get(c);
            if (v == null) continue;
            out.add(new Equivalent(c, v));
        }
        return out;
    }

    @NonNull
    public static ViewModelProvider.Factory factory(
            @NonNull Context anyContext, @NonNull GlobalFilterViewModel globalFilter) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(ValueChartViewModel.class)) {
                    return (T) new ValueChartViewModel(
                            sl.portfolioRepository(),
                            sl.userPreferences(),
                            globalFilter,
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }

    /** One entry of the cross-currency ribbon — the period-end value in {@link #currency}. */
    public static final class Equivalent {
        @NonNull public final Currency currency;
        @NonNull public final BigDecimal amount;

        public Equivalent(@NonNull Currency currency, @NonNull BigDecimal amount) {
            this.currency = currency;
            this.amount = amount;
        }
    }

    public static final class ChartData {
        /** Currency the {@code value}/{@code invested} fields are denominated in. */
        @NonNull public final Currency currency;
        @Nullable public final FilterPeriod period;
        @Nullable public final CustomRange customRange;
        @NonNull public final List<Point> points;
        public final boolean hasAnyGaps;
        /** Period-end value in the other currencies — non-null only in "All" mode. */
        @Nullable public final List<Equivalent> valueEquivalents;

        public ChartData(
                @NonNull Currency currency,
                @Nullable FilterPeriod period,
                @Nullable CustomRange customRange,
                @NonNull List<Point> points,
                boolean hasAnyGaps,
                @Nullable List<Equivalent> valueEquivalents) {
            this.currency = currency;
            this.period = period;
            this.customRange = customRange;
            this.points = points;
            this.hasAnyGaps = hasAnyGaps;
            this.valueEquivalents = valueEquivalents;
        }

        /**
         * End-of-period value, invested, and period P&amp;L. Drives the totals card
         * above the chart. Period P&amp;L is the change in (value − invested) over the
         * window — capital deposits during the window cancel from both sides, so this
         * is correctly market-only in the project's "isolate market P&L from cash
         * flows" sense.
         *
         * <p>Returns null when {@link #points} is empty.
         */
        @Nullable
        public PeriodTotals periodTotals() {
            if (points.isEmpty()) return null;
            Point first = points.get(0);
            Point last = points.get(points.size() - 1);

            BigDecimal pnl = last.value.subtract(last.invested)
                    .subtract(first.value.subtract(first.invested));
            // Headline percentage uses the same series + helper as the Portfolio
            // totals card and Growth chart, so all three render identical numbers.
            List<BigDecimal> values = new ArrayList<>(points.size());
            List<BigDecimal> investeds = new ArrayList<>(points.size());
            for (Point p : points) {
                values.add(p.value);
                investeds.add(p.invested);
            }
            List<BigDecimal> pcts = PortfolioReturnSeries.cumulativePct(values, investeds);
            BigDecimal pct = null;
            for (int idx = pcts.size() - 1; idx >= 0; idx--) {
                BigDecimal p = pcts.get(idx);
                if (p != null) { pct = p; break; }
            }
            return new PeriodTotals(currency, last.value, last.invested, pnl, pct);
        }
    }

    public static final class PeriodTotals {
        @NonNull public final Currency currency;
        @NonNull public final BigDecimal valueEnd;
        @NonNull public final BigDecimal investedEnd;
        @NonNull public final BigDecimal periodPnl;
        /** Null when starting value was zero — a percentage isn't meaningful. */
        @Nullable public final BigDecimal periodPnlPct;

        public PeriodTotals(
                @NonNull Currency currency,
                @NonNull BigDecimal valueEnd,
                @NonNull BigDecimal investedEnd,
                @NonNull BigDecimal periodPnl,
                @Nullable BigDecimal periodPnlPct) {
            this.currency = currency;
            this.valueEnd = valueEnd;
            this.investedEnd = investedEnd;
            this.periodPnl = periodPnl;
            this.periodPnlPct = periodPnlPct;
        }
    }

    public static final class Point {
        @NonNull public final LocalDate date;
        @NonNull public final BigDecimal value;
        @NonNull public final BigDecimal invested;
        public final boolean hasFxGaps;

        public Point(
                @NonNull LocalDate date,
                @NonNull BigDecimal value,
                @NonNull BigDecimal invested,
                boolean hasFxGaps) {
            this.date = date;
            this.value = value;
            this.invested = invested;
            this.hasFxGaps = hasFxGaps;
        }
    }
}
