package com.my.finmon.ui.manualevent;

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
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.FifoResult;
import com.my.finmon.data.repository.PortfolioRepository.SplitIngest;

import java.util.Collections;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Drives the "record dividend, coupon, or redemption" form. Lists tradeable
 * (STOCK/BOND) assets for the picker; on submit, dispatches to one of three repo
 * methods based on the selected kind chip + asset type:
 * <ul>
 *   <li>STOCK + INCOME → {@code recordDividendPayment}</li>
 *   <li>BOND  + INCOME → {@code recordCouponPayment}</li>
 *   <li>BOND  + MATURITY → {@code recordBondMaturity}</li>
 * </ul>
 *
 * <p>For income, refuses same-{@code (asset, date)} duplicates via
 * {@link PortfolioRepository#hasIncomeOn}. For maturity, the repo's own idempotency
 * (one MATURITY per bond ever) does the deduping.
 */
public final class ManualEventViewModel extends ViewModel {

    private static final String TAG = "ManualEventVM";

    public static final class RedemptionPreview {
        @NonNull public final BigDecimal qty;
        @NonNull public final BigDecimal face;
        @NonNull public final BigDecimal cashAmount;
        @NonNull public final Currency currency;
        public final boolean alreadyRedeemed;

        public RedemptionPreview(@NonNull BigDecimal qty, @NonNull BigDecimal face,
                                 @NonNull BigDecimal cashAmount, @NonNull Currency currency,
                                 boolean alreadyRedeemed) {
            this.qty = qty;
            this.face = face;
            this.cashAmount = cashAmount;
            this.currency = currency;
            this.alreadyRedeemed = alreadyRedeemed;
        }
    }

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<AssetEntity>> assets = new MutableLiveData<>();
    private final MutableLiveData<Boolean> saved = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<RedemptionPreview> redemptionPreview = new MutableLiveData<>();

    public ManualEventViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        loadAssets();
    }

    @NonNull public LiveData<List<AssetEntity>> assets() { return assets; }
    @NonNull public LiveData<Boolean> saved() { return saved; }
    @NonNull public LiveData<String> error() { return error; }
    @NonNull public LiveData<RedemptionPreview> redemptionPreview() { return redemptionPreview; }

    private void loadAssets() {
        viewExecutor.execute(() -> {
            try {
                assets.postValue(repo.listTradeableAssets().get());
            } catch (Exception e) {
                Log.w(TAG, "asset list failed", e);
                error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    /**
     * Compute (qty held × face) for the bond redemption preview line. Walks FIFO over
     * the bond's events as of {@code asOf} and probes for an existing MATURITY event.
     * Emits null preview if the asset isn't a bond, has no open lots, or is already
     * redeemed.
     */
    public void requestRedemptionPreview(@Nullable AssetEntity asset, @NonNull LocalDate asOf) {
        if (asset == null || asset.type != AssetType.BOND || asset.bondInitialPrice == null) {
            redemptionPreview.postValue(null);
            return;
        }
        viewExecutor.execute(() -> {
            try {
                FifoResult fifo = repo.computeFifoCostBasis(asset.id, asOf).get();
                boolean alreadyRedeemed = repo.hasMaturityFor(asset.id).get();
                BigDecimal qty = fifo.openQty;
                BigDecimal face = asset.bondInitialPrice;
                BigDecimal cash = qty.multiply(face);
                redemptionPreview.postValue(new RedemptionPreview(
                        qty, face, cash, asset.currency, alreadyRedeemed));
            } catch (Exception e) {
                Log.w(TAG, "redemption preview failed", e);
                redemptionPreview.postValue(null);
            }
        });
    }

    /** Submit a dividend/coupon (income kind). */
    public void submitIncome(
            @NonNull AssetEntity asset,
            @NonNull BigDecimal cashAmount,
            @NonNull LocalDateTime timestamp) {
        viewExecutor.execute(() -> {
            try {
                if (repo.hasIncomeOn(asset.id, timestamp.toLocalDate()).get()) {
                    error.postValue("A dividend or coupon for "
                            + asset.ticker + " on " + timestamp.toLocalDate()
                            + " is already recorded.");
                    return;
                }
                if (asset.type == AssetType.STOCK) {
                    repo.recordDividendPayment(asset.id, cashAmount, asset.currency, timestamp).get();
                } else if (asset.type == AssetType.BOND) {
                    repo.recordCouponPayment(asset.id, cashAmount, asset.currency, timestamp).get();
                } else {
                    error.postValue("Cannot record income for " + asset.type);
                    return;
                }
                saved.postValue(true);
            } catch (Exception e) {
                Log.w(TAG, "save failed", e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                error.postValue(cause.getMessage() != null ? cause.getMessage() : cause.toString());
            }
        });
    }

    /**
     * Submit a stock split. Forward 2-for-1 = ratio 2; reverse 1-for-2 = ratio 0.5.
     * Routes through {@link PortfolioRepository#ingestStockEvents} so dedup against
     * an existing same-day SPLIT row is automatic. Returns false to {@link #saved}
     * if the split was a no-op (already recorded or asset isn't a stock).
     */
    public void submitSplit(
            @NonNull AssetEntity asset,
            @NonNull BigDecimal ratio,
            @NonNull LocalDateTime timestamp) {
        viewExecutor.execute(() -> {
            try {
                if (asset.type != AssetType.STOCK) {
                    error.postValue("Splits apply to stocks only.");
                    return;
                }
                int written = repo.ingestStockEvents(
                        asset.id,
                        Collections.<PortfolioRepository.DividendIngest>emptyList(),
                        Collections.singletonList(new SplitIngest(timestamp, ratio))
                ).get();
                if (written == 0) {
                    error.postValue("A split for "
                            + asset.ticker + " on " + timestamp.toLocalDate()
                            + " is already recorded.");
                    return;
                }
                saved.postValue(true);
            } catch (Exception e) {
                Log.w(TAG, "split save failed", e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                error.postValue(cause.getMessage() != null ? cause.getMessage() : cause.toString());
            }
        });
    }

    /** Submit a bond redemption (maturity kind). */
    public void submitMaturity(
            @NonNull AssetEntity asset,
            @NonNull LocalDate atDate) {
        viewExecutor.execute(() -> {
            try {
                if (asset.type != AssetType.BOND) {
                    error.postValue("Bond redemption applies to bonds only.");
                    return;
                }
                Boolean wrote = repo.recordBondMaturity(asset.id, atDate).get();
                if (Boolean.FALSE.equals(wrote)) {
                    error.postValue("This bond is already redeemed or has no open lots.");
                    return;
                }
                saved.postValue(true);
            } catch (Exception e) {
                Log.w(TAG, "redemption save failed", e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                error.postValue(cause.getMessage() != null ? cause.getMessage() : cause.toString());
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
                if (modelClass.isAssignableFrom(ManualEventViewModel.class)) {
                    return (T) new ManualEventViewModel(sl.portfolioRepository(), sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
