package com.my.finmon.ui.addasset;

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
import com.my.finmon.data.remote.yahoo.YahooClient.SearchHit;
import com.my.finmon.data.repository.MarketDataRepository;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.ui.addtrade.AssetSuggestion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backs the Add Asset form. Three flows:
 * <ol>
 *   <li>Save: writes the AssetEntity prototype via {@code findOrCreateAsset}.</li>
 *   <li>Suggestion search: typing in the ticker field fires a debounced Yahoo
 *       search; results land in {@link #suggestions()}. Used identically to the
 *       trade form's autocomplete. Y4 will extend this with NBU bond results.</li>
 *   <li>Currency lookup: after the user picks a remote suggestion, we resolve the
 *       security's reporting currency via Yahoo's chart-meta endpoint; the result
 *       posts to {@link #resolvedCurrency()} so the Fragment can pre-fill the
 *       currency dropdown.</li>
 * </ol>
 */
public final class AddAssetViewModel extends ViewModel {

    private static final String TAG = "AddAssetViewModel";

    private final PortfolioRepository portfolio;
    private final MarketDataRepository marketData;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<AssetSuggestion>> suggestions = new MutableLiveData<>();
    private final MutableLiveData<Currency> resolvedCurrency = new MutableLiveData<>();
    private final MutableLiveData<Long> savedAssetId = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    /** Monotonic id; only the latest search's results land in LiveData. */
    private final AtomicLong searchSeq = new AtomicLong();

    public AddAssetViewModel(
            @NonNull PortfolioRepository portfolio,
            @NonNull MarketDataRepository marketData,
            @NonNull ExecutorService viewExecutor) {
        this.portfolio = portfolio;
        this.marketData = marketData;
        this.viewExecutor = viewExecutor;
    }

    @NonNull public LiveData<List<AssetSuggestion>> suggestions() { return suggestions; }
    @NonNull public LiveData<Currency> resolvedCurrency() { return resolvedCurrency; }
    @NonNull public LiveData<Long> savedAssetId() { return savedAssetId; }
    @NonNull public LiveData<String> error() { return error; }

    public void save(@NonNull AssetEntity prototype) {
        viewExecutor.execute(() -> {
            try {
                Long id = portfolio.findOrCreateAsset(prototype).get();
                savedAssetId.postValue(id);
            } catch (Exception e) {
                Log.w(TAG, "save failed", e);
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                error.postValue(cause.getMessage() != null ? cause.getMessage() : cause.toString());
            }
        });
    }

    /**
     * Yahoo-only search for now — empty query clears suggestions. Y4 will extend this
     * to also surface UA bonds via the NBU {@code depo_securities} endpoint.
     * Out-of-order responses are dropped via {@code searchSeq}.
     */
    public void search(@Nullable String query) {
        long seq = searchSeq.incrementAndGet();
        String q = (query == null) ? "" : query.trim();

        if (q.isEmpty()) {
            suggestions.postValue(new ArrayList<>());
            return;
        }

        viewExecutor.execute(() -> {
            List<AssetSuggestion> out = new ArrayList<>();
            try {
                List<SearchHit> hits = marketData.searchSymbols(q).get();
                for (SearchHit h : hits) out.add(AssetSuggestion.ofRemoteStock(h));
            } catch (Exception e) {
                Log.w(TAG, "Yahoo search failed for '" + q + "'", e);
            }
            try {
                List<NbuBondDto> bondHits = marketData.searchBonds(q).get();
                for (NbuBondDto b : bondHits) {
                    AssetSuggestion s = AssetSuggestion.ofNbuBond(b);
                    if (s != null) out.add(s);
                }
            } catch (Exception e) {
                Log.w(TAG, "NBU search failed for '" + q + "'", e);
            }
            if (seq != searchSeq.get()) return;
            suggestions.postValue(out);
        });
    }

    /**
     * Async currency lookup for a Yahoo symbol. The Fragment uses this after a pick to
     * pre-fill the currency dropdown without blocking the UI thread. Returns nothing
     * via Future — the result posts to {@link #resolvedCurrency}; unsupported currencies
     * post an error instead.
     */
    public void lookupCurrency(@NonNull String remoteSymbol) {
        viewExecutor.execute(() -> {
            try {
                String iso = marketData.lookupCurrency(remoteSymbol).get();
                if (iso == null) return;
                try {
                    resolvedCurrency.postValue(Currency.valueOf(iso));
                } catch (IllegalArgumentException ex) {
                    error.postValue("Currency " + iso + " is not supported yet");
                }
            } catch (Exception e) {
                Log.w(TAG, "lookupCurrency failed for " + remoteSymbol, e);
            }
        });
    }

    @NonNull
    public static ViewModelProvider.Factory factory(@NonNull Context anyContext) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(AddAssetViewModel.class)) {
                    return (T) new AddAssetViewModel(
                            sl.portfolioRepository(),
                            sl.marketDataRepository(),
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
