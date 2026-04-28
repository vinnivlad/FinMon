package com.my.finmon.ui.chart;

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
import com.my.finmon.data.repository.PortfolioRepository.ConvertedSnapshot;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.prefs.UserPreferences;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Backs the time-series chart. Snapshots are always stored in
 * {@code PortfolioRepository.BASE_CURRENCY} (USD); this VM converts them on the fly into
 * the user's chosen display currency via {@code getSnapshotsInDisplay}, then appends
 * today's live right-edge point from {@link PortfolioRepository#getPortfolioTotals}.
 *
 * Refreshes when the display-currency preference changes — same UTC/timezone analogy
 * as the portfolio header.
 */
public final class ChartViewModel extends ViewModel {

    private static final String TAG = "ChartVM";

    private final PortfolioRepository repo;
    private final UserPreferences prefs;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<ChartData> data = new MutableLiveData<>();

    /** Held as a field so we can detach in {@link #onCleared}. */
    private final Observer<Currency> displayCurrencyObserver = c -> refresh();

    public ChartViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull UserPreferences prefs,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.prefs = prefs;
        this.viewExecutor = viewExecutor;
        prefs.displayCurrency().observeForever(displayCurrencyObserver);
    }

    @Override
    protected void onCleared() {
        prefs.displayCurrency().removeObserver(displayCurrencyObserver);
        super.onCleared();
    }

    @NonNull public LiveData<ChartData> data() { return data; }

    public void refresh() {
        Currency displayCurrency = prefs.getDisplayCurrency();
        viewExecutor.execute(() -> {
            try {
                LocalDate today = LocalDate.now();
                // Ten years back is a sane cap — real user history won't exceed that soon,
                // and snapshots are bounded by sync-worker writes anyway.
                LocalDate from = today.minusYears(10);
                LocalDate yesterday = today.minusDays(1);

                List<ConvertedSnapshot> snapshots =
                        repo.getSnapshotsInDisplay(from, yesterday, displayCurrency).get();
                PortfolioTotals todayTotals = repo.getPortfolioTotals(today).get();

                List<Point> points = new ArrayList<>(snapshots.size() + 1);
                boolean anyGaps = false;

                for (ConvertedSnapshot s : snapshots) {
                    points.add(new Point(s.date, s.value, s.invested, s.hasFxGaps));
                    if (s.hasFxGaps) anyGaps = true;
                }

                // Today's right-edge point. Reuse the totals' display-currency map; fall
                // back to base if FX is missing for today (also marks a gap).
                BigDecimal todayValue = todayTotals.valueByDisplayCurrency.get(displayCurrency);
                BigDecimal todayInvested = todayTotals.investedByDisplayCurrency.get(displayCurrency);
                boolean todayGap = todayTotals.hasFxGaps
                        || todayValue == null
                        || todayInvested == null;
                if (todayValue == null) todayValue = todayTotals.valueInBase;
                if (todayInvested == null) todayInvested = todayTotals.investedInBase;
                points.add(new Point(today, todayValue, todayInvested, todayGap));
                if (todayGap) anyGaps = true;

                data.postValue(new ChartData(displayCurrency, points, anyGaps));
            } catch (Exception e) {
                Log.w(TAG, "refresh failed", e);
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
                if (modelClass.isAssignableFrom(ChartViewModel.class)) {
                    return (T) new ChartViewModel(
                            sl.portfolioRepository(),
                            sl.userPreferences(),
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }

    public static final class ChartData {
        /** Currency the points are expressed in (the user's chosen display currency). */
        @NonNull public final Currency displayCurrency;
        @NonNull public final List<Point> points;
        public final boolean hasAnyGaps;

        public ChartData(
                @NonNull Currency displayCurrency,
                @NonNull List<Point> points,
                boolean hasAnyGaps) {
            this.displayCurrency = displayCurrency;
            this.points = points;
            this.hasAnyGaps = hasAnyGaps;
        }
    }

    public static final class Point {
        @NonNull public final LocalDate date;
        /** Value in the {@link ChartData#displayCurrency}. */
        @NonNull public final BigDecimal value;
        /** Invested in the {@link ChartData#displayCurrency}. */
        @NonNull public final BigDecimal invested;
        public final boolean hasFxGaps;

        public Point(
                @NonNull LocalDate date,
                @NonNull BigDecimal value,
                @NonNull BigDecimal invested,
                boolean hasFxGaps) {
            this.date = date;
            this.value = value;
            this.invested = invested;
            this.hasFxGaps = hasFxGaps;
        }
    }
}
