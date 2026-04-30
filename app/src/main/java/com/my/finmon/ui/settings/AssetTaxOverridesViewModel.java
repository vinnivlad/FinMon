package com.my.finmon.ui.settings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.dao.AssetDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.prefs.UserPreferences;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Lists all stocks + bonds with their current tax-rate override (or null = use default).
 * Edits route through {@link PortfolioRepository#setAssetTaxRate} on the IO executor.
 */
public final class AssetTaxOverridesViewModel extends ViewModel {

    public static final class Row {
        public final long assetId;
        public final String ticker;
        public final String currency;
        public final AssetType type;
        @Nullable public final BigDecimal taxRatePct;
        @NonNull public final BigDecimal defaultPct;

        public Row(long assetId, @NonNull String ticker, @NonNull String currency,
                   @NonNull AssetType type, @Nullable BigDecimal taxRatePct,
                   @NonNull BigDecimal defaultPct) {
            this.assetId = assetId;
            this.ticker = ticker;
            this.currency = currency;
            this.type = type;
            this.taxRatePct = taxRatePct;
            this.defaultPct = defaultPct;
        }
    }

    private final AssetDao assetDao;
    private final PortfolioRepository portfolio;
    private final UserPreferences prefs;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<Row>> rows = new MutableLiveData<>();

    public AssetTaxOverridesViewModel(
            @NonNull AssetDao assetDao,
            @NonNull PortfolioRepository portfolio,
            @NonNull UserPreferences prefs,
            @NonNull ExecutorService viewExecutor) {
        this.assetDao = assetDao;
        this.portfolio = portfolio;
        this.prefs = prefs;
        this.viewExecutor = viewExecutor;
        refresh();
    }

    @NonNull
    public LiveData<List<Row>> rows() {
        return rows;
    }

    public void refresh() {
        viewExecutor.execute(() -> {
            List<AssetEntity> stocks = assetDao.findByType(AssetType.STOCK);
            List<AssetEntity> bonds = assetDao.findByType(AssetType.BOND);
            List<Row> out = new ArrayList<>(stocks.size() + bonds.size());
            for (AssetEntity a : stocks) out.add(toRow(a));
            for (AssetEntity a : bonds) out.add(toRow(a));
            rows.postValue(out);
        });
    }

    @NonNull
    private Row toRow(@NonNull AssetEntity a) {
        return new Row(
                a.id,
                a.ticker,
                a.currency.name(),
                a.type,
                a.taxRatePct,
                prefs.defaultRate(a.type));
    }

    public void setOverride(long assetId, @Nullable BigDecimal pct) {
        viewExecutor.execute(() -> {
            try {
                portfolio.setAssetTaxRate(assetId, pct).get();
            } catch (Exception ignored) {
            }
            refresh();
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
                if (modelClass.isAssignableFrom(AssetTaxOverridesViewModel.class)) {
                    return (T) new AssetTaxOverridesViewModel(
                            sl.database().assetDao(),
                            sl.portfolioRepository(),
                            sl.userPreferences(),
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
