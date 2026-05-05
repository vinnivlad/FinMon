package com.my.finmon.ui.charts;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.AnalyticsBreakdown;
import com.my.finmon.prefs.UserPreferences;
import com.my.finmon.ui.filter.FilterPeriod;
import com.my.finmon.ui.filter.GlobalFilterViewModel;
import com.my.finmon.ui.filter.GlobalFilterViewModel.CustomRange;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;

/**
 * Backs the Charts → Allocation page. Owns just the {@link AnalyticsBreakdown}
 * LiveData; everything else (window math, currency narrowing) flows from the
 * Activity-scoped {@link GlobalFilterViewModel}.
 *
 * <p>Pies render <em>as-of windowEnd</em> — for non-Custom periods that's today
 * (no visible change), for Custom with a past end date the user gets the historical
 * composition at that date.
 */
public final class AllocationViewModel extends ViewModel {

    private static final String TAG = "AllocationVM";

    private final PortfolioRepository repo;
    private final UserPreferences prefs;
    private final GlobalFilterViewModel filter;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<AnalyticsBreakdown> data = new MutableLiveData<>();

    private final Observer<Currency> displayCurrencyObserver = c -> refresh();
    private final Observer<Currency> filterCurrencyObserver = c -> refresh();
    private final Observer<FilterPeriod> filterPeriodObserver = p -> refresh();
    private final Observer<CustomRange> filterCustomRangeObserver = r -> refresh();

    public AllocationViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull UserPreferences prefs,
            @NonNull GlobalFilterViewModel filter,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.prefs = prefs;
        this.filter = filter;
        this.viewExecutor = viewExecutor;
        prefs.displayCurrency().observeForever(displayCurrencyObserver);
        filter.selectedCurrency().observeForever(filterCurrencyObserver);
        filter.selectedPeriod().observeForever(filterPeriodObserver);
        filter.customRange().observeForever(filterCustomRangeObserver);
    }

    @Override
    protected void onCleared() {
        prefs.displayCurrency().removeObserver(displayCurrencyObserver);
        filter.selectedCurrency().removeObserver(filterCurrencyObserver);
        filter.selectedPeriod().removeObserver(filterPeriodObserver);
        filter.customRange().removeObserver(filterCustomRangeObserver);
        super.onCleared();
    }

    @NonNull public LiveData<AnalyticsBreakdown> data() { return data; }

    public void refresh() {
        viewExecutor.execute(() -> {
            try {
                LocalDate today = LocalDate.now();
                LocalDate asOf = computeWindowEnd(today);
                Currency filterCcy = filter.selectedCurrency().getValue();
                // When the global filter is narrowed to a specific currency, render
                // slice values in that currency (mirrors Value/Growth's native-bucket
                // branch). "All" falls back to the user's preferred display currency.
                Currency displayCcy = filterCcy != null ? filterCcy : prefs.getDisplayCurrency();
                data.postValue(repo.getAnalyticsAsOf(asOf, displayCcy, filterCcy).get());
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
            }
        });
    }

    @NonNull
    private LocalDate computeWindowEnd(@NonNull LocalDate today) {
        FilterPeriod p = filter.selectedPeriod().getValue();
        CustomRange custom = filter.customRange().getValue();
        if (p == FilterPeriod.CUSTOM && custom != null) {
            return custom.to.isAfter(today) ? today : custom.to;
        }
        // Non-custom periods all end at today.
        return today;
    }

    @NonNull
    public static ViewModelProvider.Factory factory(
            @NonNull Context anyContext, @NonNull GlobalFilterViewModel globalFilter) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(AllocationViewModel.class)) {
                    return (T) new AllocationViewModel(
                            sl.portfolioRepository(),
                            sl.userPreferences(),
                            globalFilter,
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
