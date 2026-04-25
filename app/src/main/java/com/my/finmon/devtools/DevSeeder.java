package com.my.finmon.devtools;

import android.util.Log;

import androidx.annotation.NonNull;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.PortfolioValueDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.ExchangeRateEntity;
import com.my.finmon.data.entity.PortfolioValueSnapshotEntity;
import com.my.finmon.data.entity.StockPriceEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.data.repository.PortfolioRepository.Side;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only fixtures. Called from {@code FinMonApplication.onCreate()} in DEBUG builds,
 * right after the DB has been wiped, to give every launch a meaningful portfolio across
 * USD / EUR / UAH buckets (including a premium coupon-paying bond so the
 * {@link com.my.finmon.domain.BondValuator} path is exercised).
 *
 * All dates are anchored to {@link LocalDate#now()} so fixtures stay recent on every launch.
 * Deposits land at t−100d so every trade falls after its funding cash.
 */
public final class DevSeeder {

    private static final String TAG = "DevSeeder";

    private DevSeeder() {}

    /** Number of days of stub prices + FX to generate, ending at yesterday. */
    private static final int HISTORY_DAYS = 100;

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    public static void seed(@NonNull ServiceLocator sl) {
        sl.viewExecutor().execute(() -> {
            try {
                seedBlocking(
                        sl.portfolioRepository(),
                        sl.database().stockPriceDao(),
                        sl.database().exchangeRateDao(),
                        sl.database().portfolioValueDao());
                Log.i(TAG, "seeded dev fixtures");
            } catch (Exception e) {
                Log.w(TAG, "dev seed failed", e);
            }
        });
    }

    private static void seedBlocking(
            @NonNull PortfolioRepository repo,
            @NonNull StockPriceDao stockPriceDao,
            @NonNull ExchangeRateDao exchangeRateDao,
            @NonNull PortfolioValueDao portfolioValueDao) throws Exception {
        LocalDate today = LocalDate.now();

        // ── Assets ──────────────────────────────────────────────────────────
        long vooId = repo.findOrCreateAsset(stock("VOO", Currency.USD, "voo.us")).get();
        long sxr8Id = repo.findOrCreateAsset(stock("SXR8", Currency.EUR, "sxr8.de")).get();
        long bond1Id = repo.findOrCreateAsset(bond(
                "UA123456789_1",
                new BigDecimal("1000"),
                new BigDecimal("15"),
                LocalDate.of(2029, 6, 15))).get();
        long bond2Id = repo.findOrCreateAsset(bond(
                "UA987654321_1",
                new BigDecimal("1000"),
                new BigDecimal("12"),
                LocalDate.of(2028, 9, 15))).get();

        // ── Cash deposits ───────────────────────────────────────────────────
        // t−100d: initial capital across all three currencies.
        LocalDateTime d100 = today.minusDays(100).atTime(LocalTime.NOON);
        repo.recordCashDeposit(Currency.USD, new BigDecimal("10000"), d100).get();
        repo.recordCashDeposit(Currency.EUR, new BigDecimal("8000"), d100).get();
        repo.recordCashDeposit(Currency.UAH, new BigDecimal("500000"), d100).get();
        // t−65d: top-up USD. Two deposits give the chart a visible step in "Invested"
        // and cover the larger VOO buys that follow.
        repo.recordCashDeposit(Currency.USD, new BigDecimal("15000"),
                today.minusDays(65).atTime(LocalTime.NOON)).get();

        // ── Trades, chronological ───────────────────────────────────────────
        // Multiple VOO/SXR8 lots at different prices exercise per-lot P&L on the
        // breakdown screen (step 9, lot model #3). Two sells exercise FIFO realized P&L.
        //
        // VOO lot ladder (stub price drifts 500→640 over 100d, so daysAgo×1.4 is the
        // break-even price on that date):
        //   t−80d  3 @ $528  — at-stub
        //   t−60d  8 @ $540  — slightly below stub (~$556) — modest winner
        //   t−45d 10 @ $500  — well below stub (~$577) — big winner (existing)
        //   t−25d  4 @ $620  — above stub (~$605) — bought high, losing lot
        //   t−15d  5 @ $520  — well below stub (~$619) — big winner (existing)
        //   t−10d  SELL 6 @ $635  — FIFO eats t−80 (3) + 3 of t−60
        //
        // SXR8 lot ladder (stub 95→115 over 100d):
        //   t−70d  5 @ €102  — slightly above stub (~€101) — losing lot
        //   t−40d 15 @ €100  — below stub (~€107) — winner (existing)
        //   t−20d 10 @ €115  — well above stub (~€111) — bought high, losing lot
        //   t−5d   SELL 4 @ €114 — FIFO eats 4 of t−70 lot

        // Premium bond bought first so the coupon at t−30d has something to attach to.
        repo.recordStockTrade(Side.BUY, bond2Id,
                new BigDecimal("50"), new BigDecimal("1050"),
                today.minusDays(90).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.BUY, vooId,
                new BigDecimal("3"), new BigDecimal("528"),
                today.minusDays(80).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.BUY, sxr8Id,
                new BigDecimal("5"), new BigDecimal("102"),
                today.minusDays(70).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.BUY, vooId,
                new BigDecimal("8"), new BigDecimal("540"),
                today.minusDays(60).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.BUY, bond1Id,
                new BigDecimal("100"), new BigDecimal("990"),
                today.minusDays(50).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.BUY, vooId,
                new BigDecimal("10"), new BigDecimal("500"),
                today.minusDays(45).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.BUY, sxr8Id,
                new BigDecimal("15"), new BigDecimal("100"),
                today.minusDays(40).atTime(LocalTime.NOON)).get();

        // Semi-annual coupon on bond2: 50 units × face 1000 × 12% ÷ 2 = 3,000 UAH.
        repo.recordCouponPayment(bond2Id, new BigDecimal("3000"), Currency.UAH,
                today.minusDays(30).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.BUY, vooId,
                new BigDecimal("4"), new BigDecimal("620"),
                today.minusDays(25).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.BUY, sxr8Id,
                new BigDecimal("10"), new BigDecimal("115"),
                today.minusDays(20).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.BUY, vooId,
                new BigDecimal("5"), new BigDecimal("520"),
                today.minusDays(15).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.SELL, vooId,
                new BigDecimal("6"), new BigDecimal("635"),
                today.minusDays(10).atTime(LocalTime.NOON)).get();

        repo.recordStockTrade(Side.SELL, sxr8Id,
                new BigDecimal("4"), new BigDecimal("114"),
                today.minusDays(5).atTime(LocalTime.NOON)).get();

        // Stub stock_price and exchange_rate data so the Totals card has something to
        // convert with on the first onResume. No Stooq / Frankfurter calls — this lets
        // the emulator run offline and keeps API quota for real use. Periodic sync worker
        // will still overwrite these with live data if it runs and succeeds.
        stockPriceDao.upsertAll(generateStubStockPrices(today));
        exchangeRateDao.upsertAll(generateStubFxRates(today));

        // Seed daily portfolio snapshots by running real repo logic over the seeded data.
        // Covers the same window as the trades so the chart has meaningful shape on launch.
        LocalDate yesterday = today.minusDays(1);
        for (LocalDate d = today.minusDays(HISTORY_DAYS); !d.isAfter(yesterday); d = d.plusDays(1)) {
            PortfolioTotals t = repo.getPortfolioTotals(d).get();
            PortfolioValueSnapshotEntity s = new PortfolioValueSnapshotEntity();
            s.date = d;
            s.baseCurrency = t.baseCurrency;
            s.valueInBase = t.valueInBase;
            s.investedInBase = t.investedInBase;
            s.hasFxGaps = t.hasFxGaps;
            portfolioValueDao.upsert(s);
        }
    }

    // ─── Stub data generators ──────────────────────────────────────────────

    @NonNull
    private static List<StockPriceEntity> generateStubStockPrices(@NonNull LocalDate today) {
        List<StockPriceEntity> rows = new ArrayList<>(HISTORY_DAYS * 2);
        for (int daysAgo = HISTORY_DAYS; daysAgo >= 1; daysAgo--) {
            LocalDate d = today.minusDays(daysAgo);
            // VOO: 500 (100d ago) → 640 (yesterday). Linear drift; noise unnecessary.
            rows.add(stockPrice("VOO", d, 640.0 - daysAgo * 1.4));
            // SXR8: 95 → 115 over 100d.
            rows.add(stockPrice("SXR8", d, 115.0 - daysAgo * 0.2));
        }
        return rows;
    }

    @NonNull
    private static List<ExchangeRateEntity> generateStubFxRates(@NonNull LocalDate today) {
        List<ExchangeRateEntity> rows = new ArrayList<>(HISTORY_DAYS * 6);
        for (int daysAgo = HISTORY_DAYS; daysAgo >= 1; daysAgo--) {
            LocalDate d = today.minusDays(daysAgo);
            // EUR/USD: 1.10 → 1.17 over 100d (USD slowly weakening).
            // EUR/UAH: 48 → 51 over 100d (UAH slowly weakening).
            BigDecimal eurUsd = BigDecimal.valueOf(1.17 - daysAgo * 0.0007);
            BigDecimal eurUah = BigDecimal.valueOf(51.0 - daysAgo * 0.03);
            addAllSixPairs(rows, d, eurUsd, eurUah);
        }
        return rows;
    }

    /** Same six-directed-pairs fan-out as MarketDataRepository.buildFxRows. */
    private static void addAllSixPairs(
            @NonNull List<ExchangeRateEntity> out,
            @NonNull LocalDate date,
            @NonNull BigDecimal eurUsd,
            @NonNull BigDecimal eurUah) {
        out.add(fx(Currency.EUR, Currency.USD, date, eurUsd));
        out.add(fx(Currency.EUR, Currency.UAH, date, eurUah));
        out.add(fx(Currency.USD, Currency.EUR, date, BigDecimal.ONE.divide(eurUsd, MC)));
        out.add(fx(Currency.UAH, Currency.EUR, date, BigDecimal.ONE.divide(eurUah, MC)));
        out.add(fx(Currency.USD, Currency.UAH, date, eurUah.divide(eurUsd, MC)));
        out.add(fx(Currency.UAH, Currency.USD, date, eurUsd.divide(eurUah, MC)));
    }

    @NonNull
    private static StockPriceEntity stockPrice(@NonNull String ticker, @NonNull LocalDate date, double price) {
        StockPriceEntity p = new StockPriceEntity();
        p.ticker = ticker;
        p.date = date;
        p.closePrice = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
        return p;
    }

    @NonNull
    private static ExchangeRateEntity fx(
            @NonNull Currency src,
            @NonNull Currency tgt,
            @NonNull LocalDate date,
            @NonNull BigDecimal rate) {
        ExchangeRateEntity r = new ExchangeRateEntity();
        r.sourceCurrency = src;
        r.targetCurrency = tgt;
        r.date = date;
        r.rate = rate;
        return r;
    }

    @NonNull
    private static AssetEntity stock(@NonNull String ticker, @NonNull Currency ccy, @NonNull String stooq) {
        AssetEntity a = new AssetEntity();
        a.ticker = ticker;
        a.currency = ccy;
        a.type = AssetType.STOCK;
        a.stooqTicker = stooq;
        return a;
    }

    @NonNull
    private static AssetEntity bond(
            @NonNull String ticker,
            @NonNull BigDecimal face,
            @NonNull BigDecimal yieldPct,
            @NonNull LocalDate maturity) {
        AssetEntity a = new AssetEntity();
        a.ticker = ticker;
        a.currency = Currency.UAH;
        a.type = AssetType.BOND;
        a.bondInitialPrice = face;
        a.bondYieldPct = yieldPct;
        a.bondMaturityDate = maturity;
        // stooqTicker deliberately null — Ukrainian OVDPs aren't on Stooq.
        return a;
    }
}
