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

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Backs the Charts → Growth page. For each snapshot point in the active window,
 * computes percent growth of market P&amp;L relative to the value at window-start:
 *
 * <pre>
 *   pnl(t)       = value(t) − invested(t)
 *   baselinePnl  = value(start) − invested(start)
 *   growth(t)    = (pnl(t) − baselinePnl) / value(start) × 100
 * </pre>
 *
 * <p>Capital deposits during the window add equally to {@code value} and
 * {@code invested}, so they cancel from {@code pnl(t) − baselinePnl} — the curve
 * is purely market-driven, matching the project's "isolate market P&L from cash
 * flows" core. Anchored at 0% at the left edge; descends below zero on losses.
 */
public final class GrowthChartViewModel extends ViewModel {

    private static final String TAG = "GrowthChartVM";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PortfolioRepository repo;
    private final UserPreferences prefs;
    private final GlobalFilterViewModel filter;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<GrowthData> data = new MutableLiveData<>();

    /** Re-render when display currency changes — only meaningful in All-currency mode. */
    private final Observer<Currency> displayCurrencyObserver;
    private final Observer<Currency> filterCurrencyObserver = c -> refresh();
    private final Observer<FilterPeriod> filterPeriodObserver = p -> refresh();
    private final Observer<CustomRange> filterCustomRangeObserver = r -> refresh();

    public GrowthChartViewModel(
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

    @NonNull public LiveData<GrowthData> data() { return data; }

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
                    to = custom.to.isAfter(today) ? today : custom.to;
                } else {
                    FilterPeriod p = period != null ? period : FilterPeriod.ALL_TIME;
                    from = p == FilterPeriod.CUSTOM ? today.minusYears(1) : p.windowStart(today);
                    to = today;
                }

                LocalDate yesterday = today.minusDays(1);
                LocalDate snapTo = to.isAfter(yesterday) ? yesterday : to;
                boolean includeToday = !to.isBefore(today);

                // Collect raw (date, value, invested) tuples first; then derive the
                // growth-% series in one pass so the baseline is the actual first
                // point of the rendered series (not a hypothetical pre-window date).
                List<RawPoint> raw = new ArrayList<>();
                boolean anyGaps = false;
                Currency outCurrency;

                if (currency == null) {
                    Currency display = prefs.getDisplayCurrency();
                    outCurrency = display;
                    if (!snapTo.isBefore(from)) {
                        List<ConvertedSnapshot> snaps =
                                repo.getSnapshotsInDisplay(from, snapTo, display).get();
                        for (ConvertedSnapshot s : snaps) {
                            raw.add(new RawPoint(s.date, s.value, s.invested));
                            if (s.hasFxGaps) anyGaps = true;
                        }
                    }
                    if (includeToday) {
                        PortfolioTotals t = repo.getPortfolioTotals(today).get();
                        BigDecimal v = t.valueByDisplayCurrency.get(display);
                        BigDecimal i = t.investedByDisplayCurrency.get(display);
                        if (v == null) v = t.valueInBase;
                        if (i == null) i = t.investedInBase;
                        raw.add(new RawPoint(today, v, i));
                        if (t.hasFxGaps) anyGaps = true;
                    }
                } else {
                    outCurrency = currency;
                    if (!snapTo.isBefore(from)) {
                        List<ConvertedSnapshot> snaps =
                                repo.getSnapshotsForCurrency(from, snapTo, currency).get();
                        for (ConvertedSnapshot s : snaps) {
                            raw.add(new RawPoint(s.date, s.value, s.invested));
                        }
                    }
                    if (includeToday) {
                        PortfolioTotals t = repo.getPortfolioTotals(today).get();
                        NativeBucket bucket = t.bucketByCurrency.get(currency);
                        BigDecimal v = bucket != null ? bucket.value : BigDecimal.ZERO;
                        BigDecimal i = bucket != null ? bucket.invested : BigDecimal.ZERO;
                        raw.add(new RawPoint(today, v, i));
                    }
                }

                List<Point> points = new ArrayList<>(raw.size());
                if (raw.isEmpty()) {
                    data.postValue(new GrowthData(outCurrency, points, anyGaps));
                    return;
                }
                // Skip leading raw points where value=0 — they predate any holdings
                // in this currency (e.g. UAH bonds were bought before the user's
                // first USD purchase, so USD snapshots from those early dates carry
                // valueUsd=0). Anchoring the baseline there would either trip the
                // divide-by-zero guard or produce a misleading >100% jump on the
                // first non-zero day. The chart starts where the currency actually
                // came into existence in the portfolio.
                int baseIdx = 0;
                while (baseIdx < raw.size() && raw.get(baseIdx).value.signum() == 0) {
                    baseIdx++;
                }
                if (baseIdx >= raw.size()) {
                    data.postValue(new GrowthData(outCurrency, points, anyGaps));
                    return;
                }
                RawPoint base = raw.get(baseIdx);
                BigDecimal baselinePnl = base.value.subtract(base.invested);
                BigDecimal denom = base.value.abs();
                for (int idx = baseIdx; idx < raw.size(); idx++) {
                    RawPoint r = raw.get(idx);
                    BigDecimal pnl = r.value.subtract(r.invested);
                    BigDecimal pct = pnl.subtract(baselinePnl)
                            .divide(denom, MathContext.DECIMAL64)
                            .multiply(HUNDRED);
                    points.add(new Point(r.date, pct, r.value, r.invested));
                }
                data.postValue(new GrowthData(outCurrency, points, anyGaps));
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
            }
        });
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
                if (modelClass.isAssignableFrom(GrowthChartViewModel.class)) {
                    return (T) new GrowthChartViewModel(
                            sl.portfolioRepository(),
                            sl.userPreferences(),
                            globalFilter,
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }

    /** Intermediate snapshot tuple used while building the growth series. */
    private static final class RawPoint {
        @NonNull final LocalDate date;
        @NonNull final BigDecimal value;
        @NonNull final BigDecimal invested;
        RawPoint(@NonNull LocalDate date, @NonNull BigDecimal value, @NonNull BigDecimal invested) {
            this.date = date;
            this.value = value;
            this.invested = invested;
        }
    }

    public static final class GrowthData {
        /** Currency the underlying value/invested were denominated in. The growth
         *  series itself is unit-less (%) so the only role this plays is the FX-gap
         *  flag's interpretation. */
        @NonNull public final Currency currency;
        @NonNull public final List<Point> points;
        public final boolean hasFxGaps;

        public GrowthData(
                @NonNull Currency currency,
                @NonNull List<Point> points,
                boolean hasFxGaps) {
            this.currency = currency;
            this.points = points;
            this.hasFxGaps = hasFxGaps;
        }

        /** Final growth % at the rightmost point — drives the totals card headline. */
        @Nullable
        public BigDecimal endPct() {
            return points.isEmpty() ? null : points.get(points.size() - 1).pct;
        }
    }

    public static final class Point {
        @NonNull public final LocalDate date;
        /** Cumulative growth percent at this date, anchored at 0 % at window-start. */
        @NonNull public final BigDecimal pct;
        /** Raw NAV at this date — used by the page to compute monthly P&amp;L deltas
         *  for the "best &amp; worst months" list. Not used by the chart. */
        @NonNull public final BigDecimal value;
        @NonNull public final BigDecimal invested;

        public Point(
                @NonNull LocalDate date,
                @NonNull BigDecimal pct,
                @NonNull BigDecimal value,
                @NonNull BigDecimal invested) {
            this.date = date;
            this.pct = pct;
            this.value = value;
            this.invested = invested;
        }
    }
}
