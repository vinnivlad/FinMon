package com.my.finmon.ui.cash;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

/**
 * Drives the cash deposit / withdrawal form. A deposit is an {@code IN} on the
 * matching {@code CASH_*} pile, a withdrawal an {@code OUT} — the same external-capital
 * legs the JSON import writes — dispatched to {@link PortfolioRepository#recordCashDeposit}
 * / {@link PortfolioRepository#recordCashWithdrawal}.
 *
 * <p>Events are stamped at the current wall-clock instant ({@link LocalDateTime#now()}).
 * That real-time stamp carries minutes/seconds, so it never string-matches the noon
 * timestamp of a same-day trade — the trade-leg detection in
 * {@code PortfolioRepository.computeTotalsSync} (which keys on an exact timestamp
 * collision with a non-cash event) leaves it correctly classified as standalone capital.
 *
 * <p>Withdrawals are not balance-checked: the log is a faithful record of what the user
 * did, so a pile is allowed to go negative.
 */
public final class CashViewModel extends ViewModel {

    private static final String TAG = "CashVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<Boolean> saved = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    public CashViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
    }

    @NonNull public LiveData<Boolean> saved() { return saved; }
    @NonNull public LiveData<String> error() { return error; }

    /**
     * Record a deposit ({@code deposit == true}) or withdrawal on {@code currency},
     * stamped now.
     */
    public void submit(@NonNull Currency currency, @NonNull BigDecimal amount, boolean deposit) {
        viewExecutor.execute(() -> {
            try {
                LocalDateTime ts = LocalDateTime.now();
                if (deposit) {
                    repo.recordCashDeposit(currency, amount, ts).get();
                } else {
                    repo.recordCashWithdrawal(currency, amount, ts).get();
                }
                saved.postValue(true);
            } catch (Exception e) {
                Log.w(TAG, "cash save failed", e);
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
                if (modelClass.isAssignableFrom(CashViewModel.class)) {
                    return (T) new CashViewModel(sl.portfolioRepository(), sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
