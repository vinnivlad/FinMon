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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Owns the per-currency tab list + portfolio totals for the Breakdown screen. Period
 * filter state moved to the Activity-scoped {@code GlobalFilterViewModel} when the
 * global filter bar landed; child pages read it from there directly.
 */
public final class CurrencyBreakdownViewModel extends ViewModel {

    private static final String TAG = "CurrencyBreakdownVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<Currency>> currencies = new MutableLiveData<>();
    private final MutableLiveData<PortfolioTotals> totals = new MutableLiveData<>();

    public CurrencyBreakdownViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        refresh();
    }

    @NonNull public LiveData<List<Currency>> currencies() { return currencies; }
    @NonNull public LiveData<PortfolioTotals> totals() { return totals; }

    public void refresh() {
        viewExecutor.execute(() -> {
            try {
                PortfolioTotals t = repo.getPortfolioTotals(LocalDate.now()).get();
                totals.postValue(t);
                currencies.postValue(pickNonZero(t.bucketByCurrency));
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
            }
        });
    }

    @NonNull
    private static List<Currency> pickNonZero(@NonNull Map<Currency, NativeBucket> map) {
        List<Currency> out = new ArrayList<>();
        // Iterate in declaration order (USD, EUR, UAH) for stable tabs.
        for (Currency c : Currency.values()) {
            NativeBucket nb = map.get(c);
            if (nb == null) continue;
            if (nb.value.signum() == 0 && nb.invested.signum() == 0) continue;
            out.add(c);
        }
        return Collections.unmodifiableList(out);
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
