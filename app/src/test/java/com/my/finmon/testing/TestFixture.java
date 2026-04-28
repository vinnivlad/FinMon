package com.my.finmon.testing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.ExchangeRateEntity;
import com.my.finmon.data.entity.StockPriceEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-stop test fixture. Constructs {@link PortfolioRepository} backed by the in-memory
 * fake DAOs, pre-seeds the three CASH piles (matching production's first-launch seed),
 * and exposes terse builders for assets, prices, and FX rates.
 *
 * Usage:
 * <pre>{@code
 * TestFixture fx = new TestFixture();
 * long voo = fx.addStock("VOO", Currency.USD);
 * fx.repo.recordCashDeposit(Currency.USD, bd("10000"), noon(today)).get();
 * fx.repo.recordStockTrade(BUY, voo, bd("3"), bd("500"), noon(today)).get();
 * fx.shutdown();
 * }</pre>
 *
 * Always {@code shutdown()} in a JUnit {@code @After} so the executor thread doesn't leak.
 */
public final class TestFixture {

    public final FakeAssetDao assetDao = new FakeAssetDao();
    public final FakeEventDao eventDao = new FakeEventDao();
    public final FakeStockPriceDao stockPriceDao = new FakeStockPriceDao();
    public final FakeExchangeRateDao exchangeRateDao = new FakeExchangeRateDao();
    public final FakePortfolioValueDao portfolioValueDao = new FakePortfolioValueDao();

    public final ExecutorService executor = Executors.newSingleThreadExecutor();
    public final PortfolioRepository repo;

    public final long cashUsdId;
    public final long cashEurId;
    public final long cashUahId;

    public TestFixture() {
        // Seed the three cash piles. Matches FinMonDatabase's SEED_CALLBACK; the repo
        // wouldn't be able to record any trade or deposit without these rows.
        cashUsdId = seedCashAsset(Currency.USD);
        cashEurId = seedCashAsset(Currency.EUR);
        cashUahId = seedCashAsset(Currency.UAH);

        repo = new PortfolioRepository(
                assetDao, eventDao, stockPriceDao, exchangeRateDao, portfolioValueDao, executor);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ─── Asset builders ────────────────────────────────────────────────────

    public long addStock(@NonNull String ticker, @NonNull Currency currency) {
        AssetEntity a = new AssetEntity();
        a.ticker = ticker;
        a.currency = currency;
        a.type = AssetType.STOCK;
        return assetDao.insert(a);
    }

    public long addBond(
            @NonNull String ticker,
            @NonNull Currency currency,
            @NonNull BigDecimal face,
            @NonNull BigDecimal yieldPct,
            @NonNull LocalDate maturity) {
        AssetEntity a = new AssetEntity();
        a.ticker = ticker;
        a.currency = currency;
        a.type = AssetType.BOND;
        a.bondInitialPrice = face;
        a.bondYieldPct = yieldPct;
        a.bondMaturityDate = maturity;
        return assetDao.insert(a);
    }

    private long seedCashAsset(@NonNull Currency currency) {
        AssetEntity cash = new AssetEntity();
        cash.ticker = "CASH_" + currency.name();
        cash.currency = currency;
        cash.type = AssetType.CASH;
        long id = assetDao.insert(cash);
        eventDao.registerCashAssetId(id);
        return id;
    }

    // ─── Price / FX seeding ────────────────────────────────────────────────

    public void seedStockPrice(@NonNull String ticker, @NonNull LocalDate date, @NonNull String price) {
        StockPriceEntity p = new StockPriceEntity();
        p.ticker = ticker;
        p.date = date;
        p.closePrice = bd(price);
        stockPriceDao.upsert(p);
    }

    public void seedFxIdentity(@NonNull Currency src, @NonNull LocalDate date) {
        seedFx(src, src, date, "1");
    }

    public void seedFx(
            @NonNull Currency src, @NonNull Currency tgt,
            @NonNull LocalDate date, @NonNull String rate) {
        ExchangeRateEntity r = new ExchangeRateEntity();
        r.sourceCurrency = src;
        r.targetCurrency = tgt;
        r.date = date;
        r.rate = bd(rate);
        exchangeRateDao.upsert(r);
    }

    // ─── Convenience constants ─────────────────────────────────────────────

    /** Shorthand for {@link BigDecimal#valueOf(String)} so tests read like math. */
    public static BigDecimal bd(@NonNull String s) {
        return new BigDecimal(s);
    }

    /** Shorthand for noon on the given date. Matches production's trade-stamp convention. */
    public static java.time.LocalDateTime noon(@NonNull LocalDate d) {
        return d.atTime(12, 0);
    }

    /** Shorthand for 09:00 on the given date. Matches production's coupon/maturity stamp. */
    public static java.time.LocalDateTime nineAm(@NonNull LocalDate d) {
        return d.atTime(9, 0);
    }

    /**
     * BigDecimal equality by value (1.00 == 1.0). The default {@link BigDecimal#equals}
     * treats those as different because of scale; tests want value equality.
     */
    public static boolean sameValue(@Nullable BigDecimal expected, @Nullable BigDecimal actual) {
        if (expected == null || actual == null) return expected == actual;
        return expected.compareTo(actual) == 0;
    }
}
