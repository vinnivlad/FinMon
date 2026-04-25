package com.my.finmon.ui.chart;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.entity.PortfolioValueSnapshotEntity;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Backs the time-series chart: pulls stored daily snapshots + appends a live right-edge
 * point for today via {@link PortfolioRepository#getPortfolioTotals}. Today's point reflects
 * the latest-stored closes (sync worker walks ..yesterday only — no intraday feed).
 *
 * Period picker is deferred to step 9; this VM currently loads everything available.
 */
public final class ChartViewModel extends ViewModel {

    private static final String TAG = "ChartVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<ChartData> data = new MutableLiveData<>();

    public ChartViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        refresh();
    }

    @NonNull public LiveData<ChartData> data() { return data; }

    public void refresh() {
        viewExecutor.execute(() -> {
            try {
                LocalDate today = LocalDate.now();
                // Ten years back is a sane cap — real user history won't exceed that soon,
                // and snapshots are bounded by sync-worker writes anyway.
                LocalDate from = today.minusYears(10);
                LocalDate yesterday = today.minusDays(1);

                List<PortfolioValueSnapshotEntity> snapshots = repo.getSnapshots(from, yesterday).get();
                PortfolioTotals todayTotals = repo.getPortfolioTotals(today).get();

                List<Point> points = new ArrayList<>(snapshots.size() + 1);
                boolean anyGaps = false;
                Currency baseCurrency = todayTotals.baseCurrency;

                for (PortfolioValueSnapshotEntity s : snapshots) {
                    points.add(new Point(s.date, s.valueInBase, s.investedInBase, s.hasFxGaps));
                    if (s.hasFxGaps) anyGaps = true;
                    // Snapshots store the base currency they were computed in. Mixing two
                    // currencies on one chart would silently mis-scale — prefer the snapshot's
                    // own base if it differs (won't happen until Settings screen exists).
                    baseCurrency = s.baseCurrency;
                }
                points.add(new Point(
                        today,
                        todayTotals.valueInBase,
                        todayTotals.investedInBase,
                        todayTotals.hasFxGaps));
                if (todayTotals.hasFxGaps) anyGaps = true;

                data.postValue(new ChartData(todayTotals.baseCurrency, points, anyGaps));
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
                    return (T) new ChartViewModel(sl.portfolioRepository(), sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }

    public static final class ChartData {
        @NonNull public final Currency baseCurrency;
        @NonNull public final List<Point> points;
        public final boolean hasAnyGaps;

        public ChartData(@NonNull Currency baseCurrency, @NonNull List<Point> points, boolean hasAnyGaps) {
            this.baseCurrency = baseCurrency;
            this.points = points;
            this.hasAnyGaps = hasAnyGaps;
        }
    }

    public static final class Point {
        @NonNull public final LocalDate date;
        @NonNull public final BigDecimal valueInBase;
        @NonNull public final BigDecimal investedInBase;
        public final boolean hasFxGaps;

        public Point(
                @NonNull LocalDate date,
                @NonNull BigDecimal valueInBase,
                @NonNull BigDecimal investedInBase,
                boolean hasFxGaps) {
            this.date = date;
            this.valueInBase = valueInBase;
            this.investedInBase = investedInBase;
            this.hasFxGaps = hasFxGaps;
        }
    }
}
