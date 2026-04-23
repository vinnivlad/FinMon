package com.my.finmon.ui.addtrade;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.MarketDataRepository;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.Side;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Record-trade command wrapped as LiveData. {@link #loadAssets()} populates the picker;
 * {@link #save} writes the paired trade events, then fires (non-blocking) backfills to
 * fill in price + FX history from the trade date forward if needed.
 */
public final class AddTradeViewModel extends ViewModel {

    private static final String TAG = "AddTradeViewModel";

    private final PortfolioRepository portfolio;
    private final MarketDataRepository marketData;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<AssetEntity>> assets = new MutableLiveData<>();
    private final MutableLiveData<Boolean> saved = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    public AddTradeViewModel(
            @NonNull PortfolioRepository portfolio,
            @NonNull MarketDataRepository marketData,
            @NonNull ExecutorService viewExecutor) {
        this.portfolio = portfolio;
        this.marketData = marketData;
        this.viewExecutor = viewExecutor;
        loadAssets();
    }

    @NonNull public LiveData<List<AssetEntity>> assets() { return assets; }
    @NonNull public LiveData<Boolean> saved() { return saved; }
    @NonNull public LiveData<String> error() { return error; }

    public void loadAssets() {
        viewExecutor.execute(() -> {
            try {
                assets.postValue(portfolio.listTradeableAssets().get());
            } catch (Exception e) {
                Log.w(TAG, "loadAssets failed", e);
                error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    public void save(
            @NonNull Side side,
            @NonNull AssetEntity asset,
            @NonNull BigDecimal qty,
            @NonNull BigDecimal price,
            @NonNull LocalDateTime timestamp) {

        viewExecutor.execute(() -> {
            try {
                portfolio.recordStockTrade(side, asset.id, qty, price, timestamp).get();
                // Record succeeded — now kick off backfill. These are fire-and-forget;
                // the trade is already safely persisted. Failures log but don't surface
                // as errors to the Fragment.
                kickoffBackfills(asset, timestamp.toLocalDate());
                saved.postValue(true);
            } catch (Exception e) {
                Log.w(TAG, "save failed", e);
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                error.postValue(cause.getMessage() != null ? cause.getMessage() : cause.toString());
            }
        });
    }

    private void kickoffBackfills(@NonNull AssetEntity asset, @NonNull LocalDate tradeDate) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        if (tradeDate.isAfter(yesterday)) return;  // future-dated trade — periodic worker handles it

        if (asset.stooqTicker != null && !asset.stooqTicker.isBlank()) {
            marketData.fetchAndStoreStockPrices(asset.stooqTicker, asset.ticker, tradeDate, yesterday);
        }
        // FX is always needed — portfolio valuation across currencies depends on rates
        // covering every day from the earliest trade forward. Stooq-upsert semantics mean
        // re-fetching an already-stored range is harmless (idempotent).
        if (asset.currency != Currency.EUR) {
            marketData.fetchAndStoreFxRates(tradeDate, yesterday);
        }
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
