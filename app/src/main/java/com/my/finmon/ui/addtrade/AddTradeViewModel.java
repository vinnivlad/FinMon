package com.my.finmon.ui.addtrade;

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
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.remote.nbu.NbuBondDto;
import com.my.finmon.data.remote.yahoo.YahooClient;
import com.my.finmon.data.remote.yahoo.YahooClient.DailyAndEvents;
import com.my.finmon.data.remote.yahoo.YahooClient.SearchHit;
import com.my.finmon.data.repository.MarketDataRepository;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.DividendIngest;
import com.my.finmon.data.repository.PortfolioRepository.Side;
import com.my.finmon.data.repository.PortfolioRepository.SplitIngest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the trade-form. Two flows:
 * <ol>
 *   <li>Suggestion search: {@link #search(String)} merges already-stored assets with
 *       Yahoo's symbol search results. Empty query → just the local list.</li>
 *   <li>Save: handles local-pick (use existing id) and remote-pick (resolve currency
 *       via Yahoo, {@code findOrCreateAsset}, then record the trade). After save, kicks
 *       off price + dividend backfill from the trade date forward.</li>
 * </ol>
 */
public final class AddTradeViewModel extends ViewModel {

    private static final String TAG = "AddTradeViewModel";

    private final PortfolioRepository portfolio;
    private final MarketDataRepository marketData;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<AssetSuggestion>> suggestions = new MutableLiveData<>();
    private final MutableLiveData<Boolean> hasLocalAssets = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> saved = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    /** Snapshot of the local tradeable assets — used both as the empty-query result
     *  and to seed the merge in non-empty queries. */
    private List<AssetEntity> localAssetsCache = new ArrayList<>();

    /** Monotonic id; only the latest search's results land in LiveData. */
    private final AtomicLong searchSeq = new AtomicLong();

    public AddTradeViewModel(
            @NonNull PortfolioRepository portfolio,
            @NonNull MarketDataRepository marketData,
            @NonNull ExecutorService viewExecutor) {
        this.portfolio = portfolio;
        this.marketData = marketData;
        this.viewExecutor = viewExecutor;
        loadLocalAssets();
    }

    @NonNull public LiveData<List<AssetSuggestion>> suggestions() { return suggestions; }
    @NonNull public LiveData<Boolean> hasLocalAssets() { return hasLocalAssets; }
    @NonNull public LiveData<Boolean> saved() { return saved; }
    @NonNull public LiveData<String> error() { return error; }

    private void loadLocalAssets() {
        viewExecutor.execute(() -> {
            try {
                List<AssetEntity> assets = portfolio.listTradeableAssets().get();
                localAssetsCache = assets;
                hasLocalAssets.postValue(!assets.isEmpty());
                suggestions.postValue(toLocalSuggestions(assets));
            } catch (Exception e) {
                Log.w(TAG, "loadLocalAssets failed", e);
                error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    /**
     * Updates {@link #suggestions} based on the user's current text. Empty query →
     * local list. Otherwise: local-prefix-match merged with Yahoo search results,
     * deduplicated by ticker. Out-of-order responses are discarded via {@code searchSeq}.
     */
    public void search(@Nullable String query) {
        long seq = searchSeq.incrementAndGet();
        String q = (query == null) ? "" : query.trim();

        if (q.isEmpty()) {
            suggestions.postValue(toLocalSuggestions(localAssetsCache));
            return;
        }

        viewExecutor.execute(() -> {
            // Local prefix match — case-insensitive on ticker.
            String upperQ = q.toUpperCase();
            List<AssetSuggestion> merged = new ArrayList<>();
            Map<String, AssetSuggestion> byTicker = new LinkedHashMap<>();
            for (AssetEntity a : localAssetsCache) {
                if (a.ticker.toUpperCase().startsWith(upperQ)) {
                    AssetSuggestion s = AssetSuggestion.ofLocal(a);
                    byTicker.put(s.ticker.toUpperCase(), s);
                }
            }

            // Yahoo search — filter is server-side. Failures are non-fatal: we still
            // show local matches so the user isn't blocked by network issues.
            try {
                List<SearchHit> hits = marketData.searchSymbols(q).get();
                for (SearchHit h : hits) {
                    AssetSuggestion s = AssetSuggestion.ofRemoteStock(h);
                    String key = s.ticker.toUpperCase();
                    // Local takes precedence — already-stored assets keep their known currency.
                    byTicker.putIfAbsent(key, s);
                }
            } catch (Exception e) {
                Log.w(TAG, "Yahoo search failed for '" + q + "'", e);
            }

            // NBU bond search — filtered locally against the cached depository listing.
            try {
                List<NbuBondDto> bondHits = marketData.searchBonds(q).get();
                for (NbuBondDto b : bondHits) {
                    AssetSuggestion s = AssetSuggestion.ofNbuBond(b);
                    if (s == null) continue;
                    byTicker.putIfAbsent(s.ticker.toUpperCase(), s);
                }
            } catch (Exception e) {
                Log.w(TAG, "NBU search failed for '" + q + "'", e);
            }

            merged.addAll(byTicker.values());

            // Drop stale results so a slow earlier request can't overwrite a fast later one.
            if (seq != searchSeq.get()) return;
            suggestions.postValue(merged);
        });
    }

    public void save(
            @NonNull Side side,
            @NonNull AssetSuggestion sel,
            @NonNull BigDecimal qty,
            @NonNull BigDecimal price,
            @NonNull LocalDateTime timestamp) {

        viewExecutor.execute(() -> {
            try {
                long assetId = (sel.source == AssetSuggestion.Source.LOCAL)
                        ? sel.localId
                        : resolveOrCreateRemote(sel);
                if (assetId < 0) return;  // resolveOrCreateRemote already posted error

                portfolio.recordStockTrade(side, assetId, qty, price, timestamp).get();

                AssetEntity asset = lookupAsset(assetId);
                if (asset != null) {
                    kickoffBackfills(asset, timestamp.toLocalDate());
                }
                saved.postValue(true);
            } catch (Exception e) {
                Log.w(TAG, "save failed", e);
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                error.postValue(cause.getMessage() != null ? cause.getMessage() : cause.toString());
            }
        });
    }

    /**
     * Upserts the asset behind a remote pick. For Yahoo stocks, currency comes from
     * a chart-meta lookup. For NBU bonds, all fields are already on the suggestion.
     * Returns -1 on failure (error already posted).
     */
    private long resolveOrCreateRemote(@NonNull AssetSuggestion sel) {
        try {
            AssetEntity proto = new AssetEntity();
            proto.ticker = sel.ticker;
            proto.type = sel.type;
            proto.name = sel.name;

            if (sel.source == AssetSuggestion.Source.REMOTE_BOND) {
                if (sel.knownCurrency == null) {
                    error.postValue("Bond suggestion missing currency — corrupt NBU response?");
                    return -1;
                }
                proto.currency = sel.knownCurrency;
                proto.isin = sel.isin;
                proto.bondInitialPrice = sel.bondFace;
                proto.bondYieldPct = sel.bondYieldPct;
                proto.bondMaturityDate = sel.bondMaturity;
                return portfolio.findOrCreateAsset(proto).get();
            }

            // REMOTE_STOCK: resolve currency via Yahoo's chart-meta endpoint.
            String iso = marketData.lookupCurrency(sel.remoteTicker).get();
            if (iso == null) {
                error.postValue("Yahoo returned no currency for " + sel.remoteTicker);
                return -1;
            }
            Currency ccy;
            try {
                ccy = Currency.valueOf(iso);
            } catch (IllegalArgumentException ex) {
                error.postValue("Currency " + iso + " is not supported yet");
                return -1;
            }
            proto.currency = ccy;
            proto.remoteTicker = sel.remoteTicker;
            return portfolio.findOrCreateAsset(proto).get();
        } catch (Exception e) {
            Log.w(TAG, "remote asset upsert failed", e);
            error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            return -1;
        }
    }

    @Nullable
    private AssetEntity lookupAsset(long id) {
        try {
            for (AssetEntity a : portfolio.listTradeableAssets().get()) {
                if (a.id == id) return a;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void kickoffBackfills(@NonNull AssetEntity asset, @NonNull LocalDate tradeDate) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        if (tradeDate.isAfter(yesterday)) return;  // future-dated trade — periodic worker handles it

        if (asset.remoteTicker != null && !asset.remoteTicker.isBlank()) {
            // Same call returns prices + dividend/split events; ingest the latter so any
            // dividends paid since the trade date show up immediately, not on the next
            // periodic sync.
            viewExecutor.execute(() -> {
                try {
                    DailyAndEvents r = marketData.fetchAndStoreStockPricesWithEvents(
                            asset.remoteTicker, asset.ticker, tradeDate, yesterday).get();
                    List<DividendIngest> divs = new ArrayList<>(r.dividends.size());
                    for (YahooClient.DividendEvent d : r.dividends) {
                        divs.add(new DividendIngest(d.at, d.perShareAmount));
                    }
                    List<SplitIngest> splits = new ArrayList<>(r.splits.size());
                    for (YahooClient.SplitEvent s : r.splits) {
                        splits.add(new SplitIngest(s.at, s.ratio));
                    }
                    if (!divs.isEmpty() || !splits.isEmpty()) {
                        portfolio.ingestStockEvents(asset.id, divs, splits).get();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "trade-time backfill failed for " + asset.ticker, e);
                }
            });
        }
        // FX is always needed — portfolio valuation across currencies depends on rates
        // covering every day from the earliest trade forward. Re-fetching an
        // already-stored range is harmless (DAO upsert semantics).
        if (asset.currency != Currency.EUR) {
            marketData.fetchAndStoreFxRates(tradeDate, yesterday);
        }
    }

    @NonNull
    private static List<AssetSuggestion> toLocalSuggestions(@NonNull List<AssetEntity> assets) {
        List<AssetSuggestion> out = new ArrayList<>(assets.size());
        for (AssetEntity a : assets) out.add(AssetSuggestion.ofLocal(a));
        return out;
    }

    @NonNull
    public static ViewModelProvider.Factory factory(@NonNull Context anyContext) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(AddTradeViewModel.class)) {
                    return (T) new AddTradeViewModel(
                            sl.portfolioRepository(),
                            sl.marketDataRepository(),
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
