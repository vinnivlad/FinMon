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
import com.my.finmon.data.repository.PortfolioRepository.ConvertedSnapshot;
import com.my.finmon.data.repository.PortfolioRepository.NativeBucket;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.data.repository.PortfolioRepository.TradeRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Per-currency page VM. Owns the trade-row list AND the per-currency chart series for
 * one {@link Currency}, both re-queried whenever the parent's period filter changes
 * ({@link #reload(Period)}). The page's aggregate header (value / invested / P&amp;L)
 * is read from the parent's {@code PortfolioTotals} LiveData directly in the fragment
 * — no need to duplicate here.
 */
public final class CurrencyPageViewModel extends ViewModel {

    private static final String TAG = "CurrencyPageVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;
    private final Currency currency;

    private final MutableLiveData<List<TradeRow>> rows = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<ChartData> chart = new MutableLiveData<>();
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
    @NonNull public LiveData<ChartData> chart() { return chart; }
    @NonNull public Currency currency() { return currency; }

    public void reload(@NonNull Period p) {
        if (p == lastPeriod) return;  // nothing to do
        lastPeriod = p;
        viewExecutor.execute(() -> {
            LocalDate today = LocalDate.now();
            LocalDate windowStart = p.windowStart(today);

            try {
                List<TradeRow> list = repo.getTradeRows(currency, windowStart, today).get();
                rows.postValue(list);
            } catch (Exception e) {
                Log.w(TAG, "row reload failed for " + currency, e);
            }

            try {
                // Stored snapshots cover [windowStart, yesterday] in native currency;
                // append today's right-edge live point from the current totals.
                LocalDate yesterday = today.minusDays(1);
                List<ConvertedSnapshot> snapshots = (yesterday.isBefore(windowStart))
                        ? Collections.emptyList()
                        : repo.getSnapshotsForCurrency(windowStart, yesterday, currency).get();
                PortfolioTotals todayTotals = repo.getPortfolioTotals(today).get();
                NativeBucket todayBucket = todayTotals.bucketByCurrency.get(currency);

                List<Point> points = new ArrayList<>(snapshots.size() + 1);
                for (ConvertedSnapshot s : snapshots) {
                    points.add(new Point(s.date, s.value, s.invested));
                }
                BigDecimal todayValue = todayBucket != null ? todayBucket.value : BigDecimal.ZERO;
                BigDecimal todayInvested = todayBucket != null ? todayBucket.invested : BigDecimal.ZERO;
                points.add(new Point(today, todayValue, todayInvested));

                chart.postValue(new ChartData(currency, points));
            } catch (Exception e) {
                Log.w(TAG, "chart reload failed for " + currency, e);
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

    public static final class ChartData {
        @NonNull public final Currency currency;
        @NonNull public final List<Point> points;

        public ChartData(@NonNull Currency currency, @NonNull List<Point> points) {
            this.currency = currency;
            this.points = points;
        }
    }

    public static final class Point {
        @NonNull public final LocalDate date;
        @NonNull public final BigDecimal value;
        @NonNull public final BigDecimal invested;

        public Point(@NonNull LocalDate date, @NonNull BigDecimal value, @NonNull BigDecimal invested) {
            this.date = date;
            this.value = value;
            this.invested = invested;
        }
    }
}
