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
import com.my.finmon.sync.MarketDataRefreshBus;
import com.my.finmon.ui.filter.FilterPeriod;
import com.my.finmon.ui.filter.GlobalFilterViewModel;
import com.my.finmon.ui.filter.GlobalFilterViewModel.CustomRange;
import com.my.finmon.util.PortfolioReturnSeries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Drives the Portfolio screen. Reacts to the Activity-scoped
 * {@link GlobalFilterViewModel} — currency narrows the holdings list and totals
 * card to a single bucket, period switches the rendered numbers from lifetime to
 * window-scoped P&amp;L.
 *
 * <p>The totals card is fed by a single {@link TotalsCardData} LiveData whose value
 * is built atomically inside one executor task — both the period figures and the
 * cross-currency ribbon arrive together so the card never has to re-render with
 * an extra row sneaking in between the headline and invested.
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
    private final MutableLiveData<TotalsCardData> totalsCard = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    private final Observer<Currency> displayCurrencyObserver;
    private final Observer<Currency> filterCurrencyObserver = c -> refresh();
    private final Observer<FilterPeriod> filterPeriodObserver = p -> refresh();
    private final Observer<CustomRange> filterCustomRangeObserver = r -> refresh();
    /** Fresh prices / FX landed from a sync — re-derive so an open screen re-marks
     *  itself without waiting for the user to navigate away and back. */
    private final Observer<Long> marketDataObserver = r -> refresh();

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
            // mode the totals card is FX-free, so a re-derive is cheap and harmless.
            refresh();
        };
        prefs.displayCurrency().observeForever(displayCurrencyObserver);
        filter.selectedCurrency().observeForever(filterCurrencyObserver);
        filter.selectedPeriod().observeForever(filterPeriodObserver);
        filter.customRange().observeForever(filterCustomRangeObserver);
        MarketDataRefreshBus.revision().observeForever(marketDataObserver);
        refresh();
    }

    @Override
    protected void onCleared() {
        prefs.displayCurrency().removeObserver(displayCurrencyObserver);
        filter.selectedCurrency().removeObserver(filterCurrencyObserver);
        filter.selectedPeriod().removeObserver(filterPeriodObserver);
        filter.customRange().removeObserver(filterCustomRangeObserver);
        MarketDataRefreshBus.revision().removeObserver(marketDataObserver);
        super.onCleared();
    }

    @NonNull public LiveData<List<WindowedHolding>> windowedHoldings() { return windowedHoldings; }
    @NonNull public LiveData<TotalsCardData> totalsCard() { return totalsCard; }
    @NonNull public LiveData<Currency> displayCurrency() { return prefs.displayCurrency(); }
    @NonNull public LiveData<String> error() { return error; }
    @NonNull public LiveData<Currency> filterCurrency() { return filter.selectedCurrency(); }

    /**
     * Recomputes both the windowed holdings list and the totals card data inside
     * a single executor task. Posting them from the same task means consumers see
     * them as one update instead of two flickering through the main thread looper.
     */
    public void refresh() {
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

            TotalsCardData card = computeTotalsCardSync(today, w, currency);
            if (card != null) totalsCard.postValue(card);
        });
    }

    /**
     * Snapshot-based period totals + ribbon + fx-gap flag, in one shape. Same math
     * as Charts → Value's PeriodTotals so the headline and Period P&amp;L numbers
     * agree across screens for the same filter. Returns null when there isn't even
     * a today snapshot to anchor on (empty portfolio, no events at all).
     */
    @Nullable
    private TotalsCardData computeTotalsCardSync(
            @NonNull LocalDate today, @NonNull Window w, @Nullable Currency currency) {
        try {
            LocalDate yesterday = today.minusDays(1);
            LocalDate snapTo = w.to.isAfter(yesterday) ? yesterday : w.to;
            boolean includeToday = !w.to.isBefore(today);

            BigDecimal firstValue = null, firstInvested = null;
            BigDecimal lastValue = null, lastInvested = null;
            // Full window-series of (value, invested) for the TWR headline. Kept
            // parallel — same length, same chronological order.
            List<BigDecimal> seriesValues = new ArrayList<>();
            List<BigDecimal> seriesInvesteds = new ArrayList<>();
            Currency outCurrency;
            // Ribbon entries — populated only in All-currency mode from today's
            // PortfolioTotals. Specific-currency mode has no ribbon (drilled into
            // a single bucket already).
            List<RibbonEntry> ribbon = Collections.emptyList();
            boolean hasFxGaps = false;
            // Lifetime totals are needed for the ribbon AND for today's value/invested
            // when in All-currency mode. Pulled once and reused.
            PortfolioTotals lifetime = null;
            if (includeToday) {
                lifetime = repo.getPortfolioTotals(today).get();
            }

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
                        for (ConvertedSnapshot s : snaps) {
                            seriesValues.add(s.value);
                            seriesInvesteds.add(s.invested);
                        }
                    }
                }
                if (lifetime != null) {
                    BigDecimal v = lifetime.valueByDisplayCurrency.get(display);
                    BigDecimal i = lifetime.investedByDisplayCurrency.get(display);
                    if (v == null) v = lifetime.valueInBase;
                    if (i == null) i = lifetime.investedInBase;
                    if (firstValue == null) { firstValue = v; firstInvested = i; }
                    lastValue = v;
                    lastInvested = i;
                    seriesValues.add(v);
                    seriesInvesteds.add(i);
                    ribbon = buildRibbon(lifetime.valueByDisplayCurrency, display);
                    hasFxGaps = lifetime.hasFxGaps;
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
                        for (ConvertedSnapshot s : snaps) {
                            seriesValues.add(s.value);
                            seriesInvesteds.add(s.invested);
                        }
                    }
                }
                if (lifetime != null) {
                    NativeBucket bucket = lifetime.bucketByCurrency.get(currency);
                    BigDecimal v = bucket != null ? bucket.value : BigDecimal.ZERO;
                    BigDecimal i = bucket != null ? bucket.invested : BigDecimal.ZERO;
                    if (firstValue == null) { firstValue = v; firstInvested = i; }
                    lastValue = v;
                    lastInvested = i;
                    seriesValues.add(v);
                    seriesInvesteds.add(i);
                }
            }

            if (lastValue == null) return null;

            BigDecimal pnl = lastValue.subtract(lastInvested)
                    .subtract(firstValue.subtract(firstInvested));
            // Headline percentage — agrees with the Growth chart's endpoint
            // because both consume the same snapshot series through the same
            // helper, and matches the Breakdown tab's "pnl / invested" idea.
            BigDecimal pct = null;
            if (!seriesValues.isEmpty()) {
                List<BigDecimal> pcts = PortfolioReturnSeries.cumulativePct(seriesValues, seriesInvesteds);
                for (int idx = pcts.size() - 1; idx >= 0; idx--) {
                    BigDecimal p = pcts.get(idx);
                    if (p != null) { pct = p; break; }
                }
            }
            return new TotalsCardData(
                    outCurrency, lastValue, lastInvested, pnl, pct, ribbon, hasFxGaps);
        } catch (Exception e) {
            Log.w(TAG, "totals card refresh failed", e);
            error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            return null;
        }
    }

    /**
     * Build the cross-currency ribbon — the same total expressed in each Currency
     * other than {@code primary}, ordered by Currency declaration (USD, EUR, UAH)
     * for stable layout.
     */
    @NonNull
    private static List<RibbonEntry> buildRibbon(
            @NonNull Map<Currency, BigDecimal> valueByDisplayCurrency,
            @NonNull Currency primary) {
        List<RibbonEntry> out = new ArrayList<>();
        for (Currency c : Currency.values()) {
            if (c == primary) continue;
            BigDecimal v = valueByDisplayCurrency.get(c);
            if (v == null) continue;
            out.add(new RibbonEntry(c, v));
        }
        return Collections.unmodifiableList(out);
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

    /** One row of the cross-currency ribbon ("≈ 11,234 EUR · 456,789 UAH"). */
    public static final class RibbonEntry {
        @NonNull public final Currency currency;
        @NonNull public final BigDecimal amount;

        public RibbonEntry(@NonNull Currency currency, @NonNull BigDecimal amount) {
            this.currency = currency;
            this.amount = amount;
        }
    }

    /**
     * Fully-shaped data for the Portfolio totals card. {@link #ribbon} is empty in
     * specific-currency mode, populated in All mode. {@link #hasFxGaps} drives the
     * "Some FX rates missing" hint and is only ever true in All mode (specific
     * currency views are FX-free).
     */
    public static final class TotalsCardData {
        @NonNull public final Currency currency;
        @NonNull public final BigDecimal valueEnd;
        @NonNull public final BigDecimal investedEnd;
        @NonNull public final BigDecimal periodPnl;
        /** Null when starting value was zero — a percentage isn't meaningful. */
        @Nullable public final BigDecimal periodPnlPct;
        @NonNull public final List<RibbonEntry> ribbon;
        public final boolean hasFxGaps;

        public TotalsCardData(
                @NonNull Currency currency,
                @NonNull BigDecimal valueEnd,
                @NonNull BigDecimal investedEnd,
                @NonNull BigDecimal periodPnl,
                @Nullable BigDecimal periodPnlPct,
                @NonNull List<RibbonEntry> ribbon,
                boolean hasFxGaps) {
            this.currency = currency;
            this.valueEnd = valueEnd;
            this.investedEnd = investedEnd;
            this.periodPnl = periodPnl;
            this.periodPnlPct = periodPnlPct;
            this.ribbon = ribbon;
            this.hasFxGaps = hasFxGaps;
        }
    }
}
