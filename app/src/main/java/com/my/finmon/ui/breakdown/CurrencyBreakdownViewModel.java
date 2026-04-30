package com.my.finmon.ui.breakdown;

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
 * Shared ViewModel for the currency-breakdown screen (parent fragment + child pages).
 *
 * <p>Holds two pieces of state:
 * <ul>
 *   <li>{@code currencies} — the ordered list of currencies with non-zero holdings;
 *       populates the ViewPager2 pages. Refreshed from {@code getPortfolioTotals}.</li>
 *   <li>{@code period} — the active filter (All / YTD / 1m / 1y). Child pages observe
 *       this and re-query {@code getTradeRows} on change.</li>
 * </ul>
 * The period is <em>independent</em> from the chart screen's filter — the two screens
 * answer different questions, so they don't share state.
 */
public final class CurrencyBreakdownViewModel extends ViewModel {

    private static final String TAG = "CurrencyBreakdownVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<Currency>> currencies = new MutableLiveData<>();
    private final MutableLiveData<PortfolioTotals> totals = new MutableLiveData<>();
    private final MutableLiveData<Period> period = new MutableLiveData<>(Period.ALL_TIME);
    /** Window for {@link Period#CUSTOM}. Null when no custom range has been picked yet
     *  or the user has switched to a non-custom period (which clears it). */
    private final MutableLiveData<CustomRange> customRange = new MutableLiveData<>(null);

    public CurrencyBreakdownViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        refresh();
    }

    @NonNull public LiveData<List<Currency>> currencies() { return currencies; }
    @NonNull public LiveData<PortfolioTotals> totals() { return totals; }
    @NonNull public LiveData<Period> period() { return period; }
    @NonNull public LiveData<CustomRange> customRange() { return customRange; }

    public void setPeriod(@NonNull Period p) {
        // Picking any non-CUSTOM period clears the stored custom range — same convention
        // the Chart screen uses, so swapping back to Custom always re-prompts the picker.
        if (p != Period.CUSTOM) customRange.setValue(null);
        if (p != period.getValue()) period.setValue(p);
    }

    public void setCustomRange(@NonNull LocalDate from, @NonNull LocalDate to) {
        // Defensive ordering — DateRangePicker should already enforce from <= to.
        LocalDate lo = from.isAfter(to) ? to : from;
        LocalDate hi = from.isAfter(to) ? from : to;
        customRange.setValue(new CustomRange(lo, hi));
        period.setValue(Period.CUSTOM);
    }

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
