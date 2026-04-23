package com.my.finmon.ui.portfolio;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.Holding;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Drives the portfolio screen. Exposes two LiveDatas — the holdings list and the
 * portfolio-level totals (value + invested + P&amp;L + multi-currency display). Both
 * refresh together on {@link #refresh()}.
 *
 * {@code viewExecutor} is separate from {@code ioExecutor} on purpose — blocking on a
 * Future produced by the single-thread ioExecutor would deadlock if the wait happened
 * on the same thread.
 */
public final class PortfolioViewModel extends ViewModel {

    private static final String TAG = "PortfolioViewModel";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<Holding>> holdings = new MutableLiveData<>();
    private final MutableLiveData<PortfolioTotals> totals = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    public PortfolioViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        refresh();
    }

    @NonNull public LiveData<List<Holding>> holdings() { return holdings; }
    @NonNull public LiveData<PortfolioTotals> totals() { return totals; }
    @NonNull public LiveData<String> error() { return error; }

    public void refresh() {
        viewExecutor.execute(() -> {
            LocalDate today = LocalDate.now();
            try {
                holdings.postValue(repo.getHoldingsAsOf(today).get());
            } catch (Exception e) {
                Log.w(TAG, "holdings refresh failed", e);
                error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            }
            try {
                totals.postValue(repo.getPortfolioTotals(today).get());
            } catch (Exception e) {
                Log.w(TAG, "totals refresh failed", e);
                error.postValue(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    @NonNull
    public static ViewModelProvider.Factory factory(@NonNull android.content.Context anyContext) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(PortfolioViewModel.class)) {
                    return (T) new PortfolioViewModel(sl.portfolioRepository(), sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
