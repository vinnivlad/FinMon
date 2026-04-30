package com.my.finmon.ui.filter;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
 * Activity-scoped filter shared by Portfolio, Breakdown, Chart and (later) Bonds.
 * Three filter axes:
 * <ul>
 *   <li><b>Currency</b> — {@code null} = "All". Specific values narrow each consuming
 *       screen to that currency: Portfolio shows only that currency's holdings,
 *       Breakdown auto-selects that currency tab, Chart shows the FX-free native
 *       single-currency series.</li>
 *   <li><b>Period</b> — one of {@link FilterPeriod}. {@link FilterPeriod#CUSTOM}
 *       pairs with {@link #customRange}.</li>
 *   <li><b>Custom range</b> — user-picked {@code from..to}, only meaningful when
 *       period == CUSTOM.</li>
 * </ul>
 *
 * <p>{@code availableCurrencies} is a derived LiveData driven by the user's actual
 * holdings — used to populate the currency chip row dynamically. Refreshed when
 * the consumer asks (e.g. on activity resume after an import).
 *
 * <p>State is in-memory only; app restart resets to {@code (All, ALL_TIME)} — the
 * same convention Chart used.
 */
public final class GlobalFilterViewModel extends ViewModel {

    private static final String TAG = "GlobalFilterVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    /** {@code null} = "All" (FX-converted view in consumers' chosen display currency). */
    private final MutableLiveData<Currency> selectedCurrency = new MutableLiveData<>(null);
    private final MutableLiveData<FilterPeriod> selectedPeriod = new MutableLiveData<>(FilterPeriod.ALL_TIME);
    private final MutableLiveData<CustomRange> customRange = new MutableLiveData<>(null);

    /** Currencies the user actually holds — drives the dynamic chip row. */
    private final MutableLiveData<List<Currency>> availableCurrencies =
            new MutableLiveData<>(Collections.emptyList());

    public GlobalFilterViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        refreshAvailableCurrencies();
    }

    @NonNull public LiveData<Currency> selectedCurrency() { return selectedCurrency; }
    @NonNull public LiveData<FilterPeriod> selectedPeriod() { return selectedPeriod; }
    @NonNull public LiveData<CustomRange> customRange() { return customRange; }
    @NonNull public LiveData<List<Currency>> availableCurrencies() { return availableCurrencies; }

    public void setCurrency(@Nullable Currency currency) {
        if (sameCurrency(selectedCurrency.getValue(), currency)) return;
        selectedCurrency.setValue(currency);
    }

    public void setPeriod(@NonNull FilterPeriod period) {
        if (selectedPeriod.getValue() == period
                && period != FilterPeriod.CUSTOM
                && customRange.getValue() == null) {
            return;
        }
        selectedPeriod.setValue(period);
        if (period != FilterPeriod.CUSTOM) {
            customRange.setValue(null);
        }
    }

    public void setCustomRange(@NonNull LocalDate from, @NonNull LocalDate to) {
        // Defensive ordering — DateRangePicker should already enforce from <= to.
        LocalDate lo = from.isAfter(to) ? to : from;
        LocalDate hi = from.isAfter(to) ? from : to;
        customRange.setValue(new CustomRange(lo, hi));
        selectedPeriod.setValue(FilterPeriod.CUSTOM);
    }

    /**
     * Re-derive the held-currency list from {@code getPortfolioTotals}. Called by the
     * Activity on resume / after import so the chip row stays in sync with reality.
     */
    public void refreshAvailableCurrencies() {
        viewExecutor.execute(() -> {
            try {
                PortfolioTotals t = repo.getPortfolioTotals(LocalDate.now()).get();
                availableCurrencies.postValue(pickNonZero(t.bucketByCurrency));
            } catch (Exception e) {
                Log.w(TAG, "availableCurrencies refresh failed", e);
            }
        });
    }

    @NonNull
    private static List<Currency> pickNonZero(@NonNull Map<Currency, NativeBucket> map) {
        List<Currency> out = new ArrayList<>();
        // Iterate in declaration order (USD, EUR, UAH) for stable chip ordering.
        for (Currency c : Currency.values()) {
            NativeBucket nb = map.get(c);
            if (nb == null) continue;
            if (nb.value.signum() == 0 && nb.invested.signum() == 0) continue;
            out.add(c);
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean sameCurrency(@Nullable Currency a, @Nullable Currency b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a == b;
    }

    public static final class CustomRange {
        @NonNull public final LocalDate from;
        @NonNull public final LocalDate to;
        public CustomRange(@NonNull LocalDate from, @NonNull LocalDate to) {
            this.from = from;
            this.to = to;
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
                if (modelClass.isAssignableFrom(GlobalFilterViewModel.class)) {
                    return (T) new GlobalFilterViewModel(
                            sl.portfolioRepository(),
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
