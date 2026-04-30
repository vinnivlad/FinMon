package com.my.finmon.ui.portfolio;

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
import com.my.finmon.data.repository.PortfolioRepository.WindowedHolding;
import com.my.finmon.prefs.UserPreferences;
import com.my.finmon.ui.filter.FilterPeriod;
import com.my.finmon.ui.filter.GlobalFilterViewModel;
import com.my.finmon.ui.filter.GlobalFilterViewModel.CustomRange;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Drives the Portfolio screen. Reacts to the Activity-scoped
 * {@link GlobalFilterViewModel} — currency narrows the holdings list and totals
 * card to a single bucket, period switches the rendered numbers from lifetime to
 * window-scoped P&amp;L.
 *
 * <p>{@code totals} stays as a lifetime/point-in-time read used only for the
 * multi-currency ribbon ("≈ 11,234 EUR · 456,789 UAH") on the totals card. The
 * headline number, invested line, and Period P&amp;L all come from {@link #periodTotals}.
 *
 * <p>{@code viewExecutor} is separate from {@code ioExecutor} on purpose — blocking
 * on a Future produced by the single-thread ioExecutor would deadlock if the wait
 * happened on the same thread.
 */
public final class PortfolioViewModel extends ViewModel {

    private static final String TAG = "PortfolioViewModel";

    private final PortfolioRepository repo;
    private final UserPreferences prefs;
    private final GlobalFilterViewModel filter;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<WindowedHolding>> windowedHoldings = new MutableLiveData<>();
    private final MutableLiveData<PortfolioTotals> totals = new MutableLiveData<>();
    private final MutableLiveData<PeriodTotals> periodTotals = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    /**
     * Display currency only matters for the All-mode totals card here — Allocation
     * pies moved to the Charts screen and own their own VM. Initialized in the
     * constructor because it reads {@code this.filter}.
     */
    private final Observer<Currency> displayCurrencyObserver;
    private final Observer<Currency> filterCurrencyObserver = c -> refreshWindowed();
    private final Observer<FilterPeriod> filterPeriodObserver = p -> refreshWindowed();
    private final Observer<CustomRange> filterCustomRangeObserver = r -> refreshWindowed();

    public PortfolioViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull UserPreferences prefs,
            @NonNull GlobalFilterViewModel filter,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.prefs = prefs;
        this.filter = filter;
        this.viewExecutor = viewExecutor;
        this.displayCurrencyObserver = c -> {
            // Display currency drives the All-mode totals card; in specific-currency
            // mode the totals card is FX-free, so no re-derive needed.
            if (filter.selectedCurrency().getValue() == null) refreshPeriodTotals();
        };
        prefs.displayCurrency().observeForever(displayCurrencyObserver);
        filter.selectedCurrency().observeForever(filterCurrencyObserver);
        filter.selectedPeriod().observeForever(filterPeriodObserver);
        filter.customRange().observeForever(filterCustomRangeObserver);
        refresh();
    }

    @Override
    protected void onCleared() {
        prefs.displayCurrency().removeObserver(displayCurrencyObserver);
        filter.selectedCurrency().removeObserver(filterCurrencyObserver);
        filter.selectedPeriod().removeObserver(filterPeriodObserver);
        filter.customRange().removeObserver(filterCustomRangeObserver);
        super.onCleared();
    }

    @NonNull public LiveData<List<WindowedHolding>> windowedHoldings() { return windowedHoldings; }
    @NonNull public LiveData<PortfolioTotals> totals() { return totals; }
    @NonNull public LiveData<PeriodTotals> periodTotals() { return periodTotals; }
    @NonNull public LiveData<Currency> displayCurrency() { return prefs.displayCurrency(); }
    @NonNull public LiveData<String> error() { return error; }
    @NonNull public LiveData<Currency> filterCurrency() { return filter.selectedCurrency(); }

    public void refresh() {
        refreshWindowed();
        refreshLifetime();
    }

    private void refreshWindowed() {
        viewExecutor.execute(() -> {
            LocalDate today = LocalDate.now();
            Window w = computeWindow(today);
            Currency currency = filter.selectedCurrency().getValue();
            try {
                List<WindowedHolding> wh = repo.getWindowedHoldings(currency, w.from, w.to).get();
                windowedHoldings.postValue(wh);
            } catch (Exception e) {
                Log.w(TAG, "windowed holdings refresh failed", e);
                error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            }
            refreshPeriodTotalsSync(today, w, currency);
        });
    }

    private void refreshLifetime() {
        viewExecutor.execute(() -> {
            LocalDate today = LocalDate.now();
            try {
                totals.postValue(repo.getPortfolioTotals(today).get());
            } catch (Exception e) {
                Log.w(TAG, "totals refresh failed", e);
                error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    private void refreshPeriodTotals() {
        viewExecutor.execute(() -> {
            LocalDate today = LocalDate.now();
            Window w = computeWindow(today);
            refreshPeriodTotalsSync(today, w, filter.selectedCurrency().getValue());
        });
    }

    /**
     * Snapshot-based period totals — same math as Chart's PeriodTotals so the totals
     * card numbers agree across the two screens for the same filter. The first/last
     * points come from snapshots in {@code [windowStart, yesterday]} plus today's
     * live totals appended on the right edge.
     */
    private void refreshPeriodTotalsSync(
            @NonNull LocalDate today, @NonNull Window w, @Nullable Currency currency) {
        try {
            LocalDate yesterday = today.minusDays(1);
            LocalDate snapTo = w.to.isAfter(yesterday) ? yesterday : w.to;
            boolean includeToday = !w.to.isBefore(today);

            BigDecimal firstValue = null, firstInvested = null;
            BigDecimal lastValue = null, lastInvested = null;
            Currency outCurrency;

            if (currency == null) {
                Currency display = prefs.getDisplayCurrency();
                outCurrency = display;
                if (!snapTo.isBefore(w.from)) {
                    List<ConvertedSnapshot> snaps =
                            repo.getSnapshotsInDisplay(w.from, snapTo, display).get();
                    if (!snaps.isEmpty()) {
                        ConvertedSnapshot first = snaps.get(0);
                        firstValue = first.value;
                        firstInvested = first.invested;
                        ConvertedSnapshot last = snaps.get(snaps.size() - 1);
                        lastValue = last.value;
                        lastInvested = last.invested;
                    }
                }
                if (includeToday) {
                    PortfolioTotals t = repo.getPortfolioTotals(today).get();
                    BigDecimal v = t.valueByDisplayCurrency.get(display);
                    BigDecimal i = t.investedByDisplayCurrency.get(display);
                    if (v == null) v = t.valueInBase;
                    if (i == null) i = t.investedInBase;
                    if (firstValue == null) { firstValue = v; firstInvested = i; }
                    lastValue = v;
                    lastInvested = i;
                }
            } else {
                outCurrency = currency;
                if (!snapTo.isBefore(w.from)) {
                    List<ConvertedSnapshot> snaps =
                            repo.getSnapshotsForCurrency(w.from, snapTo, currency).get();
                    if (!snaps.isEmpty()) {
                        ConvertedSnapshot first = snaps.get(0);
                        firstValue = first.value;
                        firstInvested = first.invested;
                        ConvertedSnapshot last = snaps.get(snaps.size() - 1);
                        lastValue = last.value;
                        lastInvested = last.invested;
                    }
                }
                if (includeToday) {
                    PortfolioTotals t = repo.getPortfolioTotals(today).get();
                    NativeBucket bucket = t.bucketByCurrency.get(currency);
                    BigDecimal v = bucket != null ? bucket.value : BigDecimal.ZERO;
                    BigDecimal i = bucket != null ? bucket.invested : BigDecimal.ZERO;
                    if (firstValue == null) { firstValue = v; firstInvested = i; }
                    lastValue = v;
                    lastInvested = i;
                }
            }

            if (lastValue == null) {
                periodTotals.postValue(null);
                return;
            }
            BigDecimal pnl = lastValue.subtract(lastInvested)
                    .subtract(firstValue.subtract(firstInvested));
            BigDecimal pct = null;
            if (firstValue.signum() != 0) {
                pct = pnl.divide(firstValue.abs(), MathContext.DECIMAL64)
                        .multiply(new BigDecimal("100"));
            }
            periodTotals.postValue(new PeriodTotals(
                    outCurrency, lastValue, lastInvested, pnl, pct));
        } catch (Exception e) {
            Log.w(TAG, "period totals refresh failed", e);
            error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    @NonNull
    private Window computeWindow(@NonNull LocalDate today) {
        FilterPeriod p = filter.selectedPeriod().getValue();
        CustomRange custom = filter.customRange().getValue();
        if (p == FilterPeriod.CUSTOM && custom != null) {
            LocalDate to = custom.to.isAfter(today) ? today : custom.to;
            return new Window(custom.from, to);
        }
        FilterPeriod resolved = p != null ? p : FilterPeriod.ALL_TIME;
        LocalDate from = resolved == FilterPeriod.CUSTOM
                ? today.minusYears(1)
                : resolved.windowStart(today);
        return new Window(from, today);
    }

    @NonNull
    public static ViewModelProvider.Factory factory(
            @NonNull android.content.Context anyContext,
            @NonNull GlobalFilterViewModel filter) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(PortfolioViewModel.class)) {
                    return (T) new PortfolioViewModel(
                            sl.portfolioRepository(),
                            sl.userPreferences(),
                            filter,
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }

    /** Resolved [from, to] window for the current filter. */
    private static final class Window {
        @NonNull final LocalDate from;
        @NonNull final LocalDate to;
        Window(@NonNull LocalDate from, @NonNull LocalDate to) {
            this.from = from;
            this.to = to;
        }
    }

    /**
     * Headline totals card numbers for the active filter. {@code currency} is the
     * display currency in All mode, the picked currency in specific mode.
     */
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
}
