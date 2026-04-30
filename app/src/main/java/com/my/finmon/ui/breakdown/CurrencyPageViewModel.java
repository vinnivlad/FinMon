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
import com.my.finmon.data.repository.PortfolioRepository.TradeRow;
import com.my.finmon.ui.filter.FilterPeriod;
import com.my.finmon.ui.filter.GlobalFilterViewModel.CustomRange;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Per-currency page VM. Owns the trade-row list for one {@link Currency}, re-queried
 * via {@link #reload(FilterPeriod, CustomRange)} when the global filter changes.
 *
 * <p>The aggregate header (value / invested / P&amp;L / dividends / realized /
 * unrealized) is read from the parent's {@code PortfolioTotals} LiveData directly
 * in the fragment.
 */
public final class CurrencyPageViewModel extends ViewModel {

    private static final String TAG = "CurrencyPageVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;
    private final Currency currency;

    private final MutableLiveData<List<TradeRow>> rows = new MutableLiveData<>(Collections.emptyList());
    private FilterPeriod lastPeriod;
    @Nullable private CustomRange lastRange;

    public CurrencyPageViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor,
            @NonNull Currency currency) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        this.currency = currency;
    }

    @NonNull public LiveData<List<TradeRow>> rows() { return rows; }
    @NonNull public Currency currency() { return currency; }

    public void reload(@NonNull FilterPeriod p, @Nullable CustomRange range) {
        // CUSTOM is the only period that uses an external range; for non-CUSTOM the
        // range argument is irrelevant and won't trigger a reload on its own.
        boolean rangeChanged = (p == FilterPeriod.CUSTOM) && !sameRange(range, lastRange);
        if (p == lastPeriod && !rangeChanged) return;
        lastPeriod = p;
        lastRange = range;

        viewExecutor.execute(() -> {
            try {
                LocalDate today = LocalDate.now();
                LocalDate from;
                LocalDate to;
                if (p == FilterPeriod.CUSTOM) {
                    if (range == null) {
                        // No range picked yet — render empty rather than running with a
                        // bogus default. The picker will set one shortly.
                        rows.postValue(Collections.emptyList());
                        return;
                    }
                    from = range.from;
                    to = range.to.isAfter(today) ? today : range.to;
                } else {
                    from = p.windowStart(today);
                    to = today;
                }
                List<TradeRow> list = repo.getTradeRows(currency, from, to).get();
                rows.postValue(list);
            } catch (Exception e) {
                Log.w(TAG, "row reload failed for " + currency, e);
            }
        });
    }

    private static boolean sameRange(@Nullable CustomRange a, @Nullable CustomRange b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.from, b.from) && Objects.equals(a.to, b.to);
    }

    @NonNull
    public static ViewModelProvider.Factory factory(
            @NonNull Context anyContext, @NonNull Currency currency) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(CurrencyPageViewModel.class)) {
                    return (T) new CurrencyPageViewModel(
                            sl.portfolioRepository(), sl.viewExecutor(), currency);
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
