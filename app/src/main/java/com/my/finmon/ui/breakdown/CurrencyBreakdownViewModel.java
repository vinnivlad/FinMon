package com.my.finmon.ui.breakdown;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.NativeBucket;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Backs the per-currency breakdown screen. Reuses {@code PortfolioRepository.getPortfolioTotals}
 * (the same call the main header card uses) and flattens its {@code bucketByCurrency} map
 * into an ordered list. Buckets where both value and invested are zero are filtered out.
 */
public final class CurrencyBreakdownViewModel extends ViewModel {

    private static final String TAG = "CurrencyBreakdownVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<CurrencyBucket>> buckets = new MutableLiveData<>();

    public CurrencyBreakdownViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        refresh();
    }

    @NonNull public LiveData<List<CurrencyBucket>> buckets() { return buckets; }

    public void refresh() {
        viewExecutor.execute(() -> {
            try {
                PortfolioTotals totals = repo.getPortfolioTotals(LocalDate.now()).get();
                buckets.postValue(flatten(totals.bucketByCurrency));
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
            }
        });
    }

    @NonNull
    private static List<CurrencyBucket> flatten(@NonNull Map<Currency, NativeBucket> map) {
        List<CurrencyBucket> out = new ArrayList<>();
        // Iterate in Currency declaration order (USD, EUR, UAH) — stable for UI.
        for (Currency c : Currency.values()) {
            NativeBucket nb = map.get(c);
            if (nb == null) continue;
            if (nb.value.signum() == 0 && nb.invested.signum() == 0) continue;
            out.add(new CurrencyBucket(c, nb.value, nb.invested, nb.pnl));
        }
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
                if (modelClass.isAssignableFrom(CurrencyBreakdownViewModel.class)) {
                    return (T) new CurrencyBreakdownViewModel(
                            sl.portfolioRepository(),
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
