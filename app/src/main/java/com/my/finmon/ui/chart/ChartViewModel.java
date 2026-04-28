package com.my.finmon.ui.chart;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Backs the global Chart tab. Two filter axes:
 * <ul>
 *   <li><b>Currency</b> — null = "All" (FX-converted into the user's display currency
 *       from settings); non-null = one of USD / EUR / UAH (native, no FX).</li>
 *   <li><b>Period</b> — one of {@link ChartPeriod}. {@link ChartPeriod#CUSTOM} pairs
 *       with {@link #customRange} for a user-picked {@code from..to}.</li>
 * </ul>
 *
 * <p>State is in-memory only. App restart resets to {@code (All, ALL_TIME)}.
 */
public final class ChartViewModel extends ViewModel {

    private static final String TAG = "ChartVM";

    private final PortfolioRepository repo;
    private final UserPreferences prefs;
    private final ExecutorService viewExecutor;

    /** {@code null} = "All" (FX-converted view). Otherwise the picked native currency. */
    private final MutableLiveData<Currency> selectedCurrency = new MutableLiveData<>(null);
    private final MutableLiveData<ChartPeriod> selectedPeriod = new MutableLiveData<>(ChartPeriod.ALL_TIME);
    private final MutableLiveData<CustomRange> customRange = new MutableLiveData<>(null);

    private final MutableLiveData<ChartData> data = new MutableLiveData<>();

    /**
     * Re-render when the user changes the display currency in Settings, but only when
     * the active filter is "All" (the FX-converted view). For a specific-currency
     * filter the chart is FX-free and Settings has no effect on it.
     */
    private final Observer<Currency> displayCurrencyObserver = c -> {
        if (selectedCurrency.getValue() == null) refresh();
    };

    public ChartViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull UserPreferences prefs,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.prefs = prefs;
        this.viewExecutor = viewExecutor;
        prefs.displayCurrency().observeForever(displayCurrencyObserver);
    }

    @Override
    protected void onCleared() {
        prefs.displayCurrency().removeObserver(displayCurrencyObserver);
        super.onCleared();
    }

    @NonNull public LiveData<ChartData> data() { return data; }
    @NonNull public LiveData<Currency> selectedCurrency() { return selectedCurrency; }
    @NonNull public LiveData<ChartPeriod> selectedPeriod() { return selectedPeriod; }
    @NonNull public LiveData<CustomRange> customRange() { return customRange; }

    public void setCurrency(@Nullable Currency currency) {
        if (sameCurrency(selectedCurrency.getValue(), currency)) return;
        selectedCurrency.setValue(currency);
        refresh();
    }

    /** Picking any non-CUSTOM period clears the stored custom range. */
    public void setPeriod(@NonNull ChartPeriod period) {
        if (selectedPeriod.getValue() == period
                && period != ChartPeriod.CUSTOM
                && customRange.getValue() == null) {
            return;
        }
        selectedPeriod.setValue(period);
        if (period != ChartPeriod.CUSTOM) {
            customRange.setValue(null);
        }
        refresh();
    }

    public void setCustomRange(@NonNull LocalDate from, @NonNull LocalDate to) {
        // Defensive ordering — DateRangePicker is supposed to enforce from <= to but
        // the API hands two epoch-millis values and we don't want a flipped fixture
        // to create a negative-length window.
        LocalDate lo = from.isAfter(to) ? to : from;
        LocalDate hi = from.isAfter(to) ? from : to;
        customRange.setValue(new CustomRange(lo, hi));
        selectedPeriod.setValue(ChartPeriod.CUSTOM);
        refresh();
    }

    public void refresh() {
        Currency currency = selectedCurrency.getValue();
        ChartPeriod period = selectedPeriod.getValue();
        CustomRange custom = customRange.getValue();

        viewExecutor.execute(() -> {
            try {
                LocalDate today = LocalDate.now();
                LocalDate from;
                LocalDate to;
                if (period == ChartPeriod.CUSTOM && custom != null) {
                    from = custom.from;
                    // Cap "to" at today — if the user picks a future date, only history
                    // up to today exists. The right-edge live point still appends.
                    to = custom.to.isAfter(today) ? today : custom.to;
                } else {
                    ChartPeriod p = period != null ? period : ChartPeriod.ALL_TIME;
                    from = p == ChartPeriod.CUSTOM ? today.minusYears(1) : p.windowStart(today);
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
                    if (includeToday) {
                        PortfolioTotals todayTotals = repo.getPortfolioTotals(today).get();
                        BigDecimal v = todayTotals.valueByDisplayCurrency.get(display);
                        BigDecimal i = todayTotals.investedByDisplayCurrency.get(display);
                        boolean todayGap = todayTotals.hasFxGaps || v == null || i == null;
                        if (v == null) v = todayTotals.valueInBase;
                        if (i == null) i = todayTotals.investedInBase;
                        points.add(new Point(today, v, i, todayGap));
                        if (todayGap) anyGaps = true;
                    }
                    data.postValue(new ChartData(display, period, custom, points, anyGaps));
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
                    data.postValue(new ChartData(currency, period, custom, points, false));
                }
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
            }
        });
    }

    private static boolean sameCurrency(@Nullable Currency a, @Nullable Currency b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a == b;
    }

    @NonNull
    public static ViewModelProvider.Factory factory(@NonNull Context anyContext) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(ChartViewModel.class)) {
                    return (T) new ChartViewModel(
                            sl.portfolioRepository(),
                            sl.userPreferences(),
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }

    public static final class CustomRange {
        @NonNull public final LocalDate from;
        @NonNull public final LocalDate to;

        public CustomRange(@NonNull LocalDate from, @NonNull LocalDate to) {
            this.from = from;
            this.to = to;
        }
    }

    public static final class ChartData {
        /** Currency the {@code value}/{@code invested} fields are denominated in. */
        @NonNull public final Currency currency;
        @NonNull public final ChartPeriod period;
        @Nullable public final CustomRange customRange;
        @NonNull public final List<Point> points;
        public final boolean hasAnyGaps;

        public ChartData(
                @NonNull Currency currency,
                @NonNull ChartPeriod period,
                @Nullable CustomRange customRange,
                @NonNull List<Point> points,
                boolean hasAnyGaps) {
            this.currency = currency;
            this.period = period;
            this.customRange = customRange;
            this.points = points;
            this.hasAnyGaps = hasAnyGaps;
        }

        /**
         * End-of-period value, invested, and period P&amp;L. Drives the totals card
         * above the chart. Period P&amp;L is the change in (value − invested) over the
         * window — which is correct in the project's "isolate market P&L from cash
         * flows" sense: capital deposits during the window cancel from both sides.
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
            BigDecimal pct = null;
            if (first.value.signum() != 0) {
                pct = pnl.divide(first.value.abs(), java.math.MathContext.DECIMAL64)
                        .multiply(new BigDecimal("100"));
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
