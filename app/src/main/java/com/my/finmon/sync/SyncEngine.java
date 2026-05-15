package com.my.finmon.sync;

import android.util.Log;

import androidx.annotation.NonNull;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.PortfolioValueDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.PortfolioValueSnapshotEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.remote.nbu.NbuBondDto;
import com.my.finmon.data.remote.yahoo.YahooClient;
import com.my.finmon.data.remote.yahoo.YahooClient.DailyAndEvents;
import com.my.finmon.data.repository.MarketDataRepository;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.DividendIngest;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.data.repository.PortfolioRepository.SplitIngest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared sync logic for both the periodic {@link PortfolioSyncWorker} and the foreground
 * {@link StartupSyncOrchestrator}. Each stage takes a {@link ProgressCallback} so the
 * orchestrator can drive a per-stage UI progress indicator while the worker stays silent.
 *
 * <p>Stage order matters — snapshots depend on freshly-synced prices/FX/coupons, so
 * snapshots run last. Per-item failures are logged and swallowed inside each stage; only
 * structural errors (DB unreachable, etc.) bubble up.
 */
public final class SyncEngine {

    private static final String TAG = "SyncEngine";
    private static final int BOOTSTRAP_DAYS = 7;

    public enum Stage { STOCK_PRICES, FX, BOND_COUPONS, SNAPSHOTS }

    /**
     * Raised when a stage finished but with a problem worth surfacing to the user
     * (per-bond NBU schedule missing, Yahoo fetch threw for a held stock, etc.).
     * The orchestrator catches it and routes to {@code Stage.FAILED} with the
     * originating stage attached for the UI to render.
     */
    public static final class StageFailedException extends RuntimeException {
        @NonNull public final Stage stage;
        public StageFailedException(@NonNull Stage stage, @NonNull String message) {
            super(message);
            this.stage = stage;
        }
    }

    /**
     * Per-stage progress hook. {@code label} is a short human-readable string of what's
     * being processed right now (e.g. {@code "VOO"}, {@code "EUR/USD"}). For stages
     * without a meaningful per-item dimension, callers pass {@code 0/0} and a stage name.
     */
    public interface ProgressCallback {
        void onProgress(@NonNull Stage stage, int currentItem, int totalItems, @NonNull String label);

        ProgressCallback NO_OP = (s, c, t, l) -> { /* drop on the floor */ };
    }

    private SyncEngine() {}

    /**
     * Runs all four stages in order. Per-stage exceptions for individual items are
     * caught inside; this method only throws if something structural goes wrong.
     */
    public static void runAll(@NonNull ServiceLocator sl, @NonNull ProgressCallback cb) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        syncStockPrices(sl, yesterday, cb);
        syncFxRates(sl, yesterday, cb);
        syncBondCoupons(sl, cb);
        syncPortfolioSnapshots(sl, yesterday, cb);
    }

    public static void syncStockPrices(
            @NonNull ServiceLocator sl, @NonNull LocalDate yesterday, @NonNull ProgressCallback cb) {
        StockPriceDao priceDao = sl.database().stockPriceDao();
        MarketDataRepository md = sl.marketDataRepository();
        PortfolioRepository portfolio = sl.portfolioRepository();
        List<AssetEntity> stocks = sl.database().assetDao().findByType(AssetType.STOCK);

        // Only stocks with a remote ticker can be synced — count those for progress.
        int total = 0;
        for (AssetEntity s : stocks) {
            if (s.remoteTicker != null && !s.remoteTicker.isBlank()) total++;
        }

        int idx = 0;
        List<String> fetchFailed = new ArrayList<>();
        for (AssetEntity stock : stocks) {
            if (stock.remoteTicker == null || stock.remoteTicker.isBlank()) continue;
            idx++;
            cb.onProgress(Stage.STOCK_PRICES, idx, total, stock.ticker);

            LocalDate latest = priceDao.latestDate(stock.ticker);
            LocalDate from = (latest != null) ? latest.plusDays(1) : yesterday.minusDays(BOOTSTRAP_DAYS);
            if (from.isAfter(yesterday)) continue;

            try {
                DailyAndEvents result = md.fetchAndStoreStockPricesWithEvents(
                        stock.remoteTicker, stock.ticker, from, yesterday).get();
                Log.i(TAG, "Yahoo " + stock.remoteTicker + " " + from + "→" + yesterday + ": "
                        + result.prices.size() + " prices, "
                        + result.dividends.size() + " divs, "
                        + result.splits.size() + " splits");

                List<DividendIngest> divs = new ArrayList<>(result.dividends.size());
                for (YahooClient.DividendEvent d : result.dividends) {
                    divs.add(new DividendIngest(d.at, d.perShareAmount));
                }
                List<SplitIngest> splits = new ArrayList<>(result.splits.size());
                for (YahooClient.SplitEvent s : result.splits) {
                    splits.add(new SplitIngest(s.at, s.ratio));
                }
                if (!divs.isEmpty() || !splits.isEmpty()) {
                    Integer written = portfolio.ingestStockEvents(stock.id, divs, splits).get();
                    Log.i(TAG, "ingested " + written + " events for " + stock.ticker);
                }
            } catch (Exception e) {
                Log.w(TAG, "Yahoo sync failed for " + stock.ticker, e);
                fetchFailed.add(stock.ticker);
            }
        }

        if (!fetchFailed.isEmpty()) {
            throw new StageFailedException(Stage.STOCK_PRICES,
                    "Yahoo fetch failed for: " + String.join(", ", fetchFailed));
        }
    }

    public static void syncFxRates(
            @NonNull ServiceLocator sl, @NonNull LocalDate yesterday, @NonNull ProgressCallback cb) {
        ExchangeRateDao fxDao = sl.database().exchangeRateDao();
        cb.onProgress(Stage.FX, 0, 0, "EUR/USD/UAH");

        // EUR→USD is in every Frankfurter response we care about, so it's a reliable
        // bellwether for "what's the most recent FX date we have?".
        LocalDate latest = fxDao.latestDate(Currency.EUR, Currency.USD);
        LocalDate from = (latest != null) ? latest.plusDays(1) : yesterday.minusDays(BOOTSTRAP_DAYS);
        if (from.isAfter(yesterday)) return;

        try {
            Integer rows = sl.marketDataRepository().fetchAndStoreFxRates(from, yesterday).get();
            Log.i(TAG, "Frankfurter " + from + "→" + yesterday + ": " + rows + " rows");
        } catch (Exception e) {
            Log.w(TAG, "Frankfurter sync failed", e);
            throw new StageFailedException(Stage.FX,
                    "Frankfurter FX fetch failed: "
                            + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    public static void syncBondCoupons(@NonNull ServiceLocator sl, @NonNull ProgressCallback cb) {
        MarketDataRepository md = sl.marketDataRepository();
        PortfolioRepository portfolio = sl.portfolioRepository();
        List<AssetEntity> bonds = sl.database().assetDao().findByType(AssetType.BOND);
        LocalDate today = LocalDate.now();

        // Matured bonds are dropped from NBU's depo_securities feed entirely. We still
        // give them one pass through the ingest below so a maturity-day coupon that
        // missed the first run (e.g. sync fired before 09:00 on maturity day and got
        // gated as future-stamped) has a chance to land. NBU is whole-list cached, so
        // the extra ISIN probe is essentially free; absence is expected and not surfaced.
        Set<Long> maturedIds = new HashSet<>();
        for (Long id : sl.database().eventDao().findMaturedBondIds()) {
            if (id != null) maturedIds.add(id);
        }

        // Quick pre-pass: a bond's last DIVIDEND was already recorded today → the
        // per-coupon ingest path would find no new rows to write, so skip the NBU
        // lookup + DB walk entirely. Today-threshold (vs. yesterday-threshold) means
        // we re-walk on each new calendar day, which sacrifices a tiny optimisation
        // window in exchange for not relying on any assumption about NBU schedule
        // spacing — in particular, this stays correct if a bond ever has payments on
        // two consecutive calendar days (rare, but standard UAH OVDPs sometimes pair
        // a final coupon with a same-day principal, and a yesterday-threshold could
        // miss the maturity row if today's launch follows yesterday's coupon ingest).
        Set<Long> upToDateIds = new HashSet<>();
        for (AssetEntity b : bonds) {
            if (b.isin == null || b.isin.isBlank()) continue;
            LocalDateTime latest = sl.database().eventDao().findLatestDividendTimestamp(b.id);
            if (latest != null && !latest.toLocalDate().isBefore(today)) {
                upToDateIds.add(b.id);
            }
        }

        int total = 0;
        for (AssetEntity b : bonds) {
            if (b.isin == null || b.isin.isBlank()) continue;
            if (upToDateIds.contains(b.id)) continue;
            total++;
        }

        int idx = 0;
        List<String> missingFromNbu = new ArrayList<>();
        for (AssetEntity bond : bonds) {
            if (bond.isin == null || bond.isin.isBlank()) continue;
            if (upToDateIds.contains(bond.id)) {
                Log.i(TAG, "NBU skip " + bond.ticker + " — last coupon already at/after yesterday");
                continue;
            }
            idx++;
            cb.onProgress(Stage.BOND_COUPONS, idx, total, bond.ticker);

            boolean matured = maturedIds.contains(bond.id);

            NbuBondDto dto;
            try {
                dto = md.fetchOrRecallBondSchedule(bond.id, bond.isin).get();
            } catch (Exception e) {
                Log.w(TAG, "NBU lookup threw for " + bond.ticker, e);
                if (!matured) missingFromNbu.add(bond.ticker + " (" + bond.isin + ")");
                continue;
            }
            if (dto == null || dto.payments == null) {
                Log.i(TAG, "NBU + cache have no schedule for " + bond.isin);
                if (!matured) missingFromNbu.add(bond.ticker + " (" + bond.isin + ")");
                continue;
            }

            try {
                List<DividendIngest> coupons = new ArrayList<>();
                LocalDate maturityDate = null;
                for (NbuBondDto.Payment p : dto.payments) {
                    if (p == null || p.pay_date == null) continue;
                    LocalDate d;
                    try {
                        d = LocalDate.parse(p.pay_date);
                    } catch (Exception ex) {
                        continue;
                    }
                    if ("1".equals(p.pay_type)) {
                        if (p.pay_val == null) continue;
                        LocalDateTime at = LocalDateTime.of(d, LocalTime.of(9, 0));
                        coupons.add(new DividendIngest(at, new BigDecimal(p.pay_val.toString())));
                    } else if ("2".equals(p.pay_type)) {
                        if (!d.isAfter(today)) maturityDate = d;
                    }
                }
                if (!coupons.isEmpty()) {
                    Integer written = portfolio.ingestBondCoupons(bond.id, coupons).get();
                    Log.i(TAG, "NBU " + bond.isin + ": ingested " + written + " coupons");
                }
                if (maturityDate != null) {
                    Boolean newRow = portfolio.ingestBondMaturity(bond.id, maturityDate).get();
                    if (Boolean.TRUE.equals(newRow)) {
                        Log.i(TAG, "NBU " + bond.isin + ": redeemed on " + maturityDate);
                    }
                }
            } catch (Exception e) {
                // Ingest-side failure (DB write, parsing) — log but don't surface, since
                // NBU itself responded fine. Worth tracking separately if it becomes a
                // recurring issue, but most likely benign mid-DB write.
                Log.w(TAG, "ingest failed for " + bond.ticker, e);
            }
        }

        if (!missingFromNbu.isEmpty()) {
            throw new StageFailedException(Stage.BOND_COUPONS,
                    "Couldn't find coupon schedules for: " + String.join(", ", missingFromNbu));
        }
    }

    public static void syncPortfolioSnapshots(
            @NonNull ServiceLocator sl, @NonNull LocalDate yesterday, @NonNull ProgressCallback cb) {
        PortfolioValueDao snapDao = sl.database().portfolioValueDao();
        PortfolioRepository repo = sl.portfolioRepository();

        // Walk latestSnapshot+1..yesterday and write a snapshot for each missing date.
        // Cold start (no snapshots): bootstrap from the user's earliest event so a full
        // import gets full history. Falls back to yesterday-7 only if there are no events
        // at all (fresh install, no data).
        LocalDate latest = snapDao.latestDate();
        LocalDate from;
        if (latest != null) {
            from = latest.plusDays(1);
        } else {
            java.time.LocalDateTime earliest = sl.database().eventDao().earliestTimestamp();
            from = (earliest != null)
                    ? earliest.toLocalDate()
                    : yesterday.minusDays(BOOTSTRAP_DAYS);
        }

        // Batch path: load events / prices / FX once, compute each day's snapshot from
        // the in-memory cache. Per-day round-trips were the O(N²) bottleneck on large
        // back-fills (post-import).
        if (!from.isAfter(yesterday)) {
            try {
                repo.rebuildSnapshotsBatch(from, yesterday, (current, total, label) ->
                        cb.onProgress(Stage.SNAPSHOTS, current, total, label)).get();
            } catch (Exception e) {
                Log.w(TAG, "batch snapshot rebuild failed", e);
                throw new StageFailedException(Stage.SNAPSHOTS,
                        "Snapshot rebuild failed: "
                                + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }

        // Re-compute any existing gappy snapshots — FX backfill may have filled holes.
        List<PortfolioValueSnapshotEntity> gappy = snapDao.findGappyUpTo(yesterday);
        if (!gappy.isEmpty()) {
            // Cheap-enough to reuse the per-day path here; gappy lists are typically small
            // and short-lived (each FX backfill clears them).
            for (PortfolioValueSnapshotEntity old : gappy) {
                try {
                    PortfolioTotals t = repo.getPortfolioTotals(old.date).get();
                    if (!t.hasFxGaps) {
                        snapDao.upsert(toSnapshot(old.date, t));
                        Log.i(TAG, "snapshot un-gapped for " + old.date);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "snapshot re-compute failed for " + old.date, e);
                }
            }
        }
    }

    private static PortfolioValueSnapshotEntity toSnapshot(LocalDate d, PortfolioTotals t) {
        PortfolioValueSnapshotEntity s = new PortfolioValueSnapshotEntity();
        s.date = d;
        s.baseCurrency = t.baseCurrency;
        s.valueInBase = t.valueInBase;
        s.investedInBase = t.investedInBase;
        s.hasFxGaps = t.hasFxGaps;

        var usd = t.bucketByCurrency.get(Currency.USD);
        var eur = t.bucketByCurrency.get(Currency.EUR);
        var uah = t.bucketByCurrency.get(Currency.UAH);
        s.valueUsd = usd != null ? usd.value : BigDecimal.ZERO;
        s.valueEur = eur != null ? eur.value : BigDecimal.ZERO;
        s.valueUah = uah != null ? uah.value : BigDecimal.ZERO;
        s.investedUsd = usd != null ? usd.invested : BigDecimal.ZERO;
        s.investedEur = eur != null ? eur.invested : BigDecimal.ZERO;
        s.investedUah = uah != null ? uah.invested : BigDecimal.ZERO;
        return s;
    }
}
