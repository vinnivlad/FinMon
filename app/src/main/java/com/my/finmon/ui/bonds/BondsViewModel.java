package com.my.finmon.ui.bonds;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPaymentsResult;
import com.my.finmon.data.repository.PortfolioRepository.MaturedBond;
import com.my.finmon.data.repository.PortfolioRepository.WindowedHolding;
import com.my.finmon.ui.filter.FilterPeriod;
import com.my.finmon.ui.filter.GlobalFilterViewModel;
import com.my.finmon.ui.filter.GlobalFilterViewModel.CustomRange;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Drives the Bonds screen. Reuses the same windowed-holdings repo path Portfolio
 * uses, but filters down to BOND type. Reacts to the Activity-scoped global filter
 * (currency narrows; period scopes the windowed P&amp;L on the holdings list).
 *
 * <p>Matured bonds were previously rendered on Portfolio's Holdings tab; they live
 * here now so the Portfolio page focuses on active positions and Bonds owns the
 * full bond timeline (active + redeemed).
 */
public final class BondsViewModel extends ViewModel {

    private static final String TAG = "BondsViewModel";

    private final PortfolioRepository repo;
    private final GlobalFilterViewModel filter;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<WindowedHolding>> activeBonds = new MutableLiveData<>();
    private final MutableLiveData<List<MaturedBond>> maturedBonds = new MutableLiveData<>();
    private final MutableLiveData<Boolean> maturedExpanded = new MutableLiveData<>(Boolean.FALSE);
    private final MutableLiveData<ExpectedPaymentsResult> expectedPayments = new MutableLiveData<>();
    private final MutableLiveData<PaymentsLabel> paymentsLabel =
            new MutableLiveData<>(PaymentsLabel.EXPECTED);

    /**
     * Drives the Expected Payments card title:
     * <ul>
     *   <li>{@link #EXPECTED} — window starts on/after today (future-only).</li>
     *   <li>{@link #RECEIVED} — window ends on/before today (past-only).</li>
     *   <li>{@link #RECEIVED_AND_EXPECTED} — window straddles today.</li>
     * </ul>
     */
    public enum PaymentsLabel { EXPECTED, RECEIVED, RECEIVED_AND_EXPECTED }

    private final Observer<Currency> filterCurrencyObserver = c -> refresh();
    private final Observer<FilterPeriod> filterPeriodObserver = p -> refresh();
    private final Observer<CustomRange> filterCustomRangeObserver = r -> refresh();

    public BondsViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull GlobalFilterViewModel filter,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.filter = filter;
        this.viewExecutor = viewExecutor;
        filter.selectedCurrency().observeForever(filterCurrencyObserver);
        filter.selectedPeriod().observeForever(filterPeriodObserver);
        filter.customRange().observeForever(filterCustomRangeObserver);
        refresh();
    }

    @Override
    protected void onCleared() {
        filter.selectedCurrency().removeObserver(filterCurrencyObserver);
        filter.selectedPeriod().removeObserver(filterPeriodObserver);
        filter.customRange().removeObserver(filterCustomRangeObserver);
        super.onCleared();
    }

    @NonNull public LiveData<List<WindowedHolding>> activeBonds() { return activeBonds; }
    @NonNull public LiveData<List<MaturedBond>> maturedBonds() { return maturedBonds; }
    @NonNull public LiveData<Boolean> maturedExpanded() { return maturedExpanded; }
    @NonNull public LiveData<ExpectedPaymentsResult> expectedPayments() { return expectedPayments; }
    @NonNull public LiveData<PaymentsLabel> paymentsLabel() { return paymentsLabel; }
    @NonNull public LiveData<Currency> filterCurrency() { return filter.selectedCurrency(); }

    public void toggleMaturedExpanded() {
        Boolean cur = maturedExpanded.getValue();
        maturedExpanded.setValue(!Boolean.TRUE.equals(cur));
    }

    public void refresh() {
        viewExecutor.execute(() -> {
            LocalDate today = LocalDate.now();
            Window holdingsWindow = computeHoldingsWindow(today);
            Window paymentsWindow = computePaymentsWindow(today);
            paymentsLabel.postValue(labelFor(paymentsWindow, today));
            Currency currency = filter.selectedCurrency().getValue();
            try {
                List<WindowedHolding> all = repo.getWindowedHoldings(
                        currency, holdingsWindow.from, holdingsWindow.to).get();
                List<WindowedHolding> bonds = new ArrayList<>();
                for (WindowedHolding wh : all) {
                    if (wh.holding.asset.type == AssetType.BOND) bonds.add(wh);
                }
                activeBonds.postValue(bonds);
            } catch (Exception e) {
                Log.w(TAG, "active bonds refresh failed", e);
            }
            try {
                maturedBonds.postValue(repo.getMaturedBonds(today).get());
            } catch (Exception e) {
                Log.w(TAG, "matured bonds refresh failed", e);
            }
            try {
                expectedPayments.postValue(repo.getBondPaymentsInWindow(
                        paymentsWindow.from, paymentsWindow.to, today, currency).get());
            } catch (Exception e) {
                Log.w(TAG, "bond payments refresh failed", e);
            }
        });
    }

    /** Window for the windowed-P&L holdings query — clamped at today since that
     *  query is meaningful only up to the present. */
    @NonNull
    private Window computeHoldingsWindow(@NonNull LocalDate today) {
        FilterPeriod p = filter.selectedPeriod().getValue();
        CustomRange custom = filter.customRange().getValue();
        if (p == FilterPeriod.CUSTOM && custom != null) {
            LocalDate to = custom.to.isAfter(today) ? today : custom.to;
            return new Window(custom.from, to);
        }
        FilterPeriod resolved = p != null ? p : FilterPeriod.ALL_TIME;
        LocalDate from = resolved == FilterPeriod.CUSTOM
                ? today.minusYears(1)
                : resolved.windowStart(today);
        return new Window(from, today);
    }

    /** Window for the bond payments query (Calendar tab + Expected Payments card).
     *  Unlike holdings, this window can extend into the future — the user picks a
     *  custom range that covers upcoming coupons (the "2026 to 2027" use case).
     *
     *  <p>ALL_TIME defaults to <b>future-only</b> so the card matches its label
     *  ("Expected payments") and the totals stay comparable to the legacy view.
     *  Past payments are reachable via past-anchored periods (1y, YTD, etc.) or a
     *  Custom range covering past dates. */
    @NonNull
    private Window computePaymentsWindow(@NonNull LocalDate today) {
        FilterPeriod p = filter.selectedPeriod().getValue();
        CustomRange custom = filter.customRange().getValue();
        if (p == FilterPeriod.CUSTOM && custom != null) {
            // Honor the user's range as-is — both endpoints can be future.
            return new Window(custom.from, custom.to);
        }
        FilterPeriod resolved = p != null ? p : FilterPeriod.ALL_TIME;
        if (resolved == FilterPeriod.ALL_TIME) {
            // Future-only: legacy "Expected payments" semantics. 30y forward covers
            // the longest realistic bond schedule; NBU caps each bond at its own
            // maturity so we don't actually project 30y of phantom payments.
            return new Window(today, today.plusYears(30));
        }
        LocalDate from = resolved == FilterPeriod.CUSTOM
                ? today.minusYears(1)
                : resolved.windowStart(today);
        return new Window(from, today);
    }

    private static final class Window {
        @NonNull final LocalDate from;
        @NonNull final LocalDate to;
        Window(@NonNull LocalDate from, @NonNull LocalDate to) {
            this.from = from;
            this.to = to;
        }
    }

    @NonNull
    static PaymentsLabel labelFor(@NonNull Window window, @NonNull LocalDate today) {
        // End on/before today → no future portion → "Received".
        if (!window.to.isAfter(today)) return PaymentsLabel.RECEIVED;
        // Start on/after today → no past portion → "Expected".
        if (!window.from.isBefore(today)) return PaymentsLabel.EXPECTED;
        // Straddles today → both sides contribute.
        return PaymentsLabel.RECEIVED_AND_EXPECTED;
    }

    @NonNull
    public static ViewModelProvider.Factory factory(
            @NonNull Context anyContext, @NonNull GlobalFilterViewModel filter) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(BondsViewModel.class)) {
                    return (T) new BondsViewModel(
                            sl.portfolioRepository(),
                            filter,
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
