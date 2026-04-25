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
import com.my.finmon.data.repository.PortfolioRepository.TradeRow;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Per-currency page VM. Owns the trade-row list for one {@link Currency}, re-queried
 * whenever the parent's period filter changes (parent calls {@link #reload(Period)}).
 * The page's aggregate header (value / invested / P&amp;L) is read from the parent's
 * {@code PortfolioTotals} LiveData directly in the fragment — no need to duplicate here.
 */
public final class CurrencyPageViewModel extends ViewModel {

    private static final String TAG = "CurrencyPageVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;
    private final Currency currency;

    private final MutableLiveData<List<TradeRow>> rows = new MutableLiveData<>(Collections.emptyList());
    private Period lastPeriod;

    public CurrencyPageViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor,
            @NonNull Currency currency) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        this.currency = currency;
    }

    @NonNull public LiveData<List<TradeRow>> rows() { return rows; }

    public void reload(@NonNull Period p) {
        if (p == lastPeriod) return;  // nothing to do
        lastPeriod = p;
        viewExecutor.execute(() -> {
            try {
                LocalDate today = LocalDate.now();
                List<TradeRow> list = repo.getTradeRows(currency, p.windowStart(today), today).get();
                rows.postValue(list);
            } catch (Exception e) {
                Log.w(TAG, "reload failed for " + currency, e);
            }
        });
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
