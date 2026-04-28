package com.my.finmon.ui.market;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.remote.yahoo.YahooClient.MarketSeries;
import com.my.finmon.data.remote.yahoo.YahooClient.SeriesPoint;
import com.my.finmon.data.repository.MarketDataRepository;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.ui.addtrade.AssetSuggestion;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backs the Market browser tab. Holds:
 * <ul>
 *   <li>{@link #heldStocks} — chip row data for currently-held STOCK assets.</li>
 *   <li>{@link #suggestions} — autocomplete results for the search field (local
 *       prefix-match merged with Yahoo's symbol search).</li>
 *   <li>{@link #pickedSymbol} — currently-selected Yahoo symbol; null when nothing's picked.</li>
 *   <li>{@link #series} — fetched price series for the picked symbol + active period.</li>
 *   <li>{@link #error} — non-null when the most recent fetch failed.</li>
 * </ul>
 *
 * <p>State is in-memory only. Period defaults to {@link MarketPeriod#ONE_DAY}.
 */
public final class MarketViewModel extends ViewModel {

    private static final String TAG = "MarketVM";

    private final PortfolioRepository portfolio;
    private final MarketDataRepository market;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<AssetEntity>> heldStocks = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<List<AssetSuggestion>> suggestions = new MutableLiveData<>(Collections.emptyList());

    private final MutableLiveData<String> pickedSymbol = new MutableLiveData<>(null);
    private final MutableLiveData<MarketPeriod> selectedPeriod = new MutableLiveData<>(MarketPeriod.ONE_DAY);
    private final MutableLiveData<CustomRange> customRange = new MutableLiveData<>(null);
    private final MutableLiveData<MarketSeries> series = new MutableLiveData<>(null);
    private final MutableLiveData<String> error = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);

    private final AtomicLong searchSeq = new AtomicLong();
    private final AtomicLong fetchSeq = new AtomicLong();

    private List<AssetEntity> heldStocksCache = new ArrayList<>();

    public MarketViewModel(
            @NonNull PortfolioRepository portfolio,
            @NonNull MarketDataRepository market,
            @NonNull ExecutorService viewExecutor) {
        this.portfolio = portfolio;
        this.market = market;
        this.viewExecutor = viewExecutor;
        loadHeldStocks();
    }

    @NonNull public LiveData<List<AssetEntity>> heldStocks() { return heldStocks; }
    @NonNull public LiveData<List<AssetSuggestion>> suggestions() { return suggestions; }
    @NonNull public LiveData<String> pickedSymbol() { return pickedSymbol; }
    @NonNull public LiveData<MarketPeriod> selectedPeriod() { return selectedPeriod; }
    @NonNull public LiveData<CustomRange> customRange() { return customRange; }
    @NonNull public LiveData<MarketSeries> series() { return series; }
    @NonNull public LiveData<String> error() { return error; }
    @NonNull public LiveData<Boolean> loading() { return loading; }

    private void loadHeldStocks() {
        viewExecutor.execute(() -> {
            try {
                List<AssetEntity> all = portfolio.listTradeableAssets().get();
                List<AssetEntity> stocks = new ArrayList<>();
                for (AssetEntity a : all) {
                    if (a.type == AssetType.STOCK
                            && a.remoteTicker != null && !a.remoteTicker.isBlank()) {
                        stocks.add(a);
                    }
                }
                heldStocksCache = stocks;
                heldStocks.postValue(stocks);
            } catch (Exception e) {
                Log.w(TAG, "loadHeldStocks failed", e);
            }
        });
    }

    /**
     * Updates {@link #suggestions} for the search field. Empty query → empty list (the
     * held-stock chip row already covers the local case, so we don't need to surface
     * locals in the dropdown when the user hasn't typed anything).
     */
    public void search(@Nullable String query) {
        long seq = searchSeq.incrementAndGet();
        String q = (query == null) ? "" : query.trim();
        if (q.isEmpty()) {
            suggestions.postValue(Collections.emptyList());
            return;
        }
        viewExecutor.execute(() -> {
            try {
                List<com.my.finmon.data.remote.yahoo.YahooClient.SearchHit> hits =
                        market.searchSymbols(q).get();
                if (seq != searchSeq.get()) return;  // stale
                List<AssetSuggestion> out = new ArrayList<>(hits.size());
                for (com.my.finmon.data.remote.yahoo.YahooClient.SearchHit h : hits) {
                    out.add(AssetSuggestion.ofRemoteStock(h));
                }
                suggestions.postValue(out);
            } catch (Exception e) {
                Log.w(TAG, "search failed", e);
            }
        });
    }

    /** Pick a held stock — short-circuits to the asset's known remoteTicker. */
    public void pickHeldStock(@NonNull AssetEntity asset) {
        if (asset.remoteTicker == null || asset.remoteTicker.isBlank()) return;
        pickSymbol(asset.remoteTicker);
    }

    /** Pick a Yahoo search result. */
    public void pickSuggestion(@NonNull AssetSuggestion s) {
        if (s.remoteTicker == null || s.remoteTicker.isBlank()) return;
        pickSymbol(s.remoteTicker);
    }

    public void pickSymbol(@NonNull String yahooSymbol) {
        if (yahooSymbol.equals(pickedSymbol.getValue())) {
            // Same pick — re-fetch (period might have changed in between).
            refetch();
            return;
        }
        pickedSymbol.setValue(yahooSymbol);
        refetch();
    }

    public void setPeriod(@NonNull MarketPeriod period) {
        if (selectedPeriod.getValue() == period
                && period != MarketPeriod.CUSTOM
                && customRange.getValue() == null) {
            return;
        }
        selectedPeriod.setValue(period);
        if (period != MarketPeriod.CUSTOM) {
            customRange.setValue(null);
        }
        refetch();
    }

    public void setCustomRange(@NonNull LocalDate from, @NonNull LocalDate to) {
        LocalDate lo = from.isAfter(to) ? to : from;
        LocalDate hi = from.isAfter(to) ? from : to;
        customRange.setValue(new CustomRange(lo, hi));
        selectedPeriod.setValue(MarketPeriod.CUSTOM);
        refetch();
    }

    /**
     * Re-fetch the series for the current symbol + period. Out-of-order responses are
     * dropped via {@link #fetchSeq}.
     */
    public void refetch() {
        String symbol = pickedSymbol.getValue();
        if (symbol == null || symbol.isBlank()) {
            series.postValue(null);
            return;
        }
        MarketPeriod period = selectedPeriod.getValue();
        CustomRange custom = customRange.getValue();
        long seq = fetchSeq.incrementAndGet();
        loading.postValue(true);
        error.postValue(null);
        viewExecutor.execute(() -> {
            try {
                MarketSeries s;
                if (period == MarketPeriod.CUSTOM && custom != null) {
                    long p1 = custom.from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                    long p2 = custom.to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                    s = market.fetchSeriesWindow(symbol, period.interval, p1, p2).get();
                } else {
                    MarketPeriod p = period != null ? period : MarketPeriod.ONE_DAY;
                    if (p == MarketPeriod.CUSTOM) p = MarketPeriod.ONE_DAY;
                    s = market.fetchSeriesByRange(symbol, p.interval, p.range).get();
                }
                if (seq != fetchSeq.get()) return;
                series.postValue(s);
            } catch (Exception e) {
                Log.w(TAG, "fetch failed", e);
                if (seq == fetchSeq.get()) {
                    error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
                }
            } finally {
                if (seq == fetchSeq.get()) loading.postValue(false);
            }
        });
    }

    @NonNull
    public List<AssetEntity> currentHeldStocks() {
        return heldStocksCache;
    }

    @NonNull
    public static ViewModelProvider.Factory factory(@NonNull Context anyContext) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(MarketViewModel.class)) {
                    return (T) new MarketViewModel(
                            sl.portfolioRepository(),
                            sl.marketDataRepository(),
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

    /**
     * Convenience: most-recent point in a series, or null if the series is empty.
     * Used by the fragment to render the header card.
     */
    @Nullable
    public static SeriesPoint lastPoint(@Nullable MarketSeries s) {
        if (s == null || s.points.isEmpty()) return null;
        return s.points.get(s.points.size() - 1);
    }

    @Nullable
    public static SeriesPoint firstPoint(@Nullable MarketSeries s) {
        if (s == null || s.points.isEmpty()) return null;
        return s.points.get(0);
    }
}
