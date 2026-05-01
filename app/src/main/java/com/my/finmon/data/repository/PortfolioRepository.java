package com.my.finmon.data.repository;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.finmon.data.dao.AssetDao;
import com.my.finmon.data.dao.EventDao;
import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.PortfolioValueDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.EventEntity;
import com.my.finmon.data.entity.ExchangeRateEntity;
import com.my.finmon.data.entity.PortfolioValueSnapshotEntity;
import com.my.finmon.data.entity.StockPriceEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.model.EventType;
import com.my.finmon.data.remote.nbu.NbuBondDto;
import com.my.finmon.data.remote.nbu.NbuClient;
import com.my.finmon.domain.BondValuator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Domain-level access to the portfolio. Wraps the DAOs with operations that enforce
 * invariants: trade pairs written atomically, cash-leg price always 1, investment income
 * (coupons, dividends) tagged with incomeSourceAssetId, FIFO computed at query time.
 *
 * All methods dispatch to the injected ExecutorService; callers must not touch the
 * returned Future on the main thread without moving the blocking get() off it.
 */
public final class PortfolioRepository {

    public enum Side { BUY, SELL }

    /**
     * Reporting currency for portfolio-level totals. Hardcoded for now; a Settings screen
     * will lift this into a SharedPreference in a later pass (see feature wishlist).
     */
    public static final Currency BASE_CURRENCY = Currency.USD;

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final AssetDao assetDao;
    private final EventDao eventDao;
    private final StockPriceDao stockPriceDao;
    private final ExchangeRateDao exchangeRateDao;
    private final PortfolioValueDao portfolioValueDao;
    private final ExecutorService executor;
    private final TaxRates taxRates;
    /** Optional — null in tests. {@link #getBondPaymentsInWindow} skips the future leg when null. */
    @Nullable private final NbuClient nbuClient;

    public PortfolioRepository(
            @NonNull AssetDao assetDao,
            @NonNull EventDao eventDao,
            @NonNull StockPriceDao stockPriceDao,
            @NonNull ExchangeRateDao exchangeRateDao,
            @NonNull PortfolioValueDao portfolioValueDao,
            @NonNull ExecutorService executor) {
        this(assetDao, eventDao, stockPriceDao, exchangeRateDao, portfolioValueDao,
                executor, TaxRates.ZERO, null);
    }

    public PortfolioRepository(
            @NonNull AssetDao assetDao,
            @NonNull EventDao eventDao,
            @NonNull StockPriceDao stockPriceDao,
            @NonNull ExchangeRateDao exchangeRateDao,
            @NonNull PortfolioValueDao portfolioValueDao,
            @NonNull ExecutorService executor,
            @NonNull TaxRates taxRates) {
        this(assetDao, eventDao, stockPriceDao, exchangeRateDao, portfolioValueDao,
                executor, taxRates, null);
    }

    public PortfolioRepository(
            @NonNull AssetDao assetDao,
            @NonNull EventDao eventDao,
            @NonNull StockPriceDao stockPriceDao,
            @NonNull ExchangeRateDao exchangeRateDao,
            @NonNull PortfolioValueDao portfolioValueDao,
            @NonNull ExecutorService executor,
            @NonNull TaxRates taxRates,
            @Nullable NbuClient nbuClient) {
        this.assetDao = assetDao;
        this.eventDao = eventDao;
        this.stockPriceDao = stockPriceDao;
        this.exchangeRateDao = exchangeRateDao;
        this.portfolioValueDao = portfolioValueDao;
        this.executor = executor;
        this.taxRates = taxRates;
        this.nbuClient = nbuClient;
    }

    /**
     * Effective tax rate for an asset (percent — 15 means 15%). Per-asset
     * override wins; falls back to the user's default for the asset's type.
     */
    @NonNull
    private BigDecimal effectiveTaxRatePct(@NonNull AssetEntity asset) {
        if (asset.taxRatePct != null) return asset.taxRatePct;
        return taxRates.defaultRate(asset.type);
    }

    /** {@code gross × (1 − rate/100)}. Returns gross unchanged when rate is null/zero. */
    @NonNull
    private static BigDecimal applyTax(@NonNull BigDecimal gross, @NonNull BigDecimal taxPct) {
        if (taxPct.signum() == 0) return gross;
        BigDecimal multiplier = BigDecimal.ONE.subtract(
                taxPct.divide(BigDecimal.valueOf(100), MC));
        return gross.multiply(multiplier, MC);
    }

    // ─── Commands ──────────────────────────────────────────────────────────

    /**
     * Records a buy or sell as a two-event pair (asset leg + cash leg), inserted
     * atomically via {@link EventDao#insertTradePair}. The cash pile is resolved from
     * the traded asset's own currency.
     */
    public Future<?> recordStockTrade(
            @NonNull Side side,
            long assetId,
            @NonNull BigDecimal qty,
            @NonNull BigDecimal pricePerUnit,
            @NonNull LocalDateTime timestamp) {
        return executor.submit(() -> {
            AssetEntity asset = assetDao.findById(assetId);
            if (asset == null) {
                throw new IllegalArgumentException("No asset with id " + assetId);
            }
            if (asset.type == AssetType.CASH) {
                throw new IllegalArgumentException(
                        "recordStockTrade is for STOCK/BOND; use recordCashDeposit/Withdrawal for cash");
            }

            AssetEntity cashAsset = requireCashAsset(asset.currency);
            BigDecimal cashAmount = qty.multiply(pricePerUnit);

            EventEntity assetLeg = new EventEntity();
            assetLeg.timestamp = timestamp;
            assetLeg.type = (side == Side.BUY) ? EventType.IN : EventType.OUT;
            assetLeg.assetId = asset.id;
            assetLeg.amount = qty;
            assetLeg.price = pricePerUnit;

            EventEntity cashLeg = new EventEntity();
            cashLeg.timestamp = timestamp;
            cashLeg.type = (side == Side.BUY) ? EventType.OUT : EventType.IN;
            cashLeg.assetId = cashAsset.id;
            cashLeg.amount = cashAmount;
            cashLeg.price = BigDecimal.ONE;

            eventDao.insertTradePair(assetLeg, cashLeg);
        });
    }

    public Future<?> recordCashDeposit(
            @NonNull Currency currency,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime timestamp) {
        return executor.submit(() -> writeCashEvent(currency, EventType.IN, amount, timestamp, null));
    }

    public Future<?> recordCashWithdrawal(
            @NonNull Currency currency,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime timestamp) {
        return executor.submit(() -> writeCashEvent(currency, EventType.OUT, amount, timestamp, null));
    }

    /**
     * Records an in-brokerage cash conversion (e.g. EUR → USD). Two events written
     * atomically via {@link EventDao#insertTradePair}: a {@link EventType#CONVERSION_OUT}
     * on the source cash pile and a {@link EventType#CONVERSION_IN} on the target
     * cash pile, both at {@code timestamp}.
     *
     * <p>The pair preserves the actual FX rate the user got from the brokerage — we
     * store {@code fromAmount} and {@code toAmount} as the user supplied them rather
     * than deriving one from the other via a market rate. Brokerage spread shows up
     * as a small FX P&amp;L drift, which is correct.
     *
     * <p>Throws if the two currencies match — that's a no-op the caller should reject
     * before submitting.
     */
    public Future<?> recordCashConversion(
            @NonNull Currency fromCurrency,
            @NonNull BigDecimal fromAmount,
            @NonNull Currency toCurrency,
            @NonNull BigDecimal toAmount,
            @NonNull LocalDateTime timestamp) {
        return executor.submit(() -> {
            if (fromCurrency == toCurrency) {
                throw new IllegalArgumentException(
                        "Cash conversion requires two different currencies, got " + fromCurrency);
            }
            if (fromAmount.signum() <= 0 || toAmount.signum() <= 0) {
                throw new IllegalArgumentException("Conversion amounts must be positive");
            }
            AssetEntity fromCash = requireCashAsset(fromCurrency);
            AssetEntity toCash = requireCashAsset(toCurrency);

            // Refuse to drain a cash pile below zero. Balance is evaluated as-of the
            // recording timestamp so back-dated conversions are validated against the
            // historical pile, not today's. Re-importing or fixing data later remains
            // open via the same recording path.
            BigDecimal balance = sumCashNet(eventDao.getByAssetAsOf(fromCash.id, timestamp));
            if (fromAmount.compareTo(balance) > 0) {
                throw new IllegalArgumentException(
                        "Insufficient " + fromCurrency + " balance: have "
                                + balance.toPlainString() + ", need " + fromAmount.toPlainString());
            }

            EventEntity outLeg = new EventEntity();
            outLeg.timestamp = timestamp;
            outLeg.type = EventType.CONVERSION_OUT;
            outLeg.assetId = fromCash.id;
            outLeg.amount = fromAmount;
            outLeg.price = BigDecimal.ONE;

            EventEntity inLeg = new EventEntity();
            inLeg.timestamp = timestamp;
            inLeg.type = EventType.CONVERSION_IN;
            inLeg.assetId = toCash.id;
            inLeg.amount = toAmount;
            inLeg.price = BigDecimal.ONE;

            eventDao.insertTradePair(outLeg, inLeg);
        });
    }

    /**
     * Probe: is there already a DIVIDEND event (stock dividend or bond coupon) recorded
     * against {@code sourceAssetId} on {@code date}? Used by the manual-entry form to
     * refuse same-day duplicates — the auto-ingest path already dedupes via the same
     * underlying check.
     */
    @NonNull
    public Future<Boolean> hasIncomeOn(long sourceAssetId, @NonNull LocalDate date) {
        return executor.submit(() -> {
            LocalDateTime startOfDay = date.atStartOfDay();
            LocalDateTime endExcl = startOfDay.plusDays(1);
            return eventDao.findDividendOnDate(sourceAssetId, startOfDay, endExcl) != null;
        });
    }

    /**
     * A coupon paid by a bond. Lands on the cash pile for {@code currency}, tagged with
     * {@code bondAssetId} as the income source so the bond valuator subtracts it from
     * accrued yield (see project_domain_model.md).
     */
    public Future<?> recordCouponPayment(
            long bondAssetId,
            @NonNull BigDecimal cashAmount,
            @NonNull Currency currency,
            @NonNull LocalDateTime timestamp) {
        return executor.submit(() ->
                recordInvestmentIncome(bondAssetId, AssetType.BOND, cashAmount, currency, timestamp));
    }

    /**
     * A dividend paid by a stock. Lands on the cash pile for {@code currency}, tagged with
     * {@code stockAssetId} as the income source — counted as return-on-investment in P&L,
     * not as new capital.
     */
    public Future<?> recordDividendPayment(
            long stockAssetId,
            @NonNull BigDecimal cashAmount,
            @NonNull Currency currency,
            @NonNull LocalDateTime timestamp) {
        return executor.submit(() ->
                recordInvestmentIncome(stockAssetId, AssetType.STOCK, cashAmount, currency, timestamp));
    }

    /**
     * Ingests stock events fetched from a remote source (today: Yahoo). Idempotent:
     * dedupes splits by (assetId, date) and dividends by (incomeSourceAssetId, date).
     * Splits are processed first in chronological order so the FIFO walk used to compute
     * held-quantity for each dividend already reflects them.
     *
     * <p>Per-share dividend amount is multiplied by the qty held on the ex-date (FIFO
     * walk over events up to that date). If the user wasn't holding the stock on the
     * ex-date, the dividend is skipped — that's correct, no income was actually paid.
     *
     * <p>Returns the count of new events written. Failures on individual entries log
     * and continue — one bad row shouldn't block the rest.
     */
    @NonNull
    public Future<Integer> ingestStockEvents(
            long stockAssetId,
            @NonNull List<DividendIngest> dividends,
            @NonNull List<SplitIngest> splits) {
        return executor.submit(() -> ingestStockEventsSync(stockAssetId, dividends, splits));
    }

    private int ingestStockEventsSync(
            long stockAssetId, List<DividendIngest> dividends, List<SplitIngest> splits) {
        AssetEntity stock = assetDao.findById(stockAssetId);
        if (stock == null || stock.type != AssetType.STOCK) {
            return 0;
        }

        int written = 0;

        // 1) Splits first, chronological. Dividend qty calculation will see them.
        List<SplitIngest> splitsSorted = new ArrayList<>(splits);
        splitsSorted.sort(Comparator.comparing(s -> s.at));
        for (SplitIngest s : splitsSorted) {
            if (s.ratio == null || s.ratio.signum() <= 0) continue;
            LocalDateTime startOfDay = s.at.toLocalDate().atStartOfDay();
            LocalDateTime endExcl = startOfDay.plusDays(1);
            if (eventDao.findSplitOnDate(stockAssetId, startOfDay, endExcl) != null) continue;

            EventEntity ev = new EventEntity();
            ev.timestamp = s.at;
            ev.type = EventType.SPLIT;
            ev.assetId = stockAssetId;
            ev.amount = s.ratio;
            ev.price = BigDecimal.ONE;  // unused for SPLIT
            eventDao.insert(ev);
            written++;
        }

        // 2) Dividends, chronological. Qty held = FIFO openQty as of ex-date end-of-day.
        List<DividendIngest> divsSorted = new ArrayList<>(dividends);
        divsSorted.sort(Comparator.comparing(d -> d.at));
        for (DividendIngest d : divsSorted) {
            if (d.perShareAmount == null || d.perShareAmount.signum() <= 0) continue;
            LocalDateTime startOfDay = d.at.toLocalDate().atStartOfDay();
            LocalDateTime endExcl = startOfDay.plusDays(1);
            if (eventDao.findDividendOnDate(stockAssetId, startOfDay, endExcl) != null) continue;

            // Qty held on ex-date — walk FIFO over events up to end-of-day, including any
            // SPLIT events written above.
            FifoResult fifo = computeFifo(eventDao.getByAssetAsOf(stockAssetId, endOfDay(d.at.toLocalDate())));
            if (fifo.openQty.signum() <= 0) continue;  // we didn't hold the stock then

            // Yahoo gives gross dividends; user actually receives net of withholding tax.
            // Storing net keeps the cash pile aligned with the brokerage account.
            BigDecimal gross = fifo.openQty.multiply(d.perShareAmount);
            BigDecimal cash = applyTax(gross, effectiveTaxRatePct(stock));
            writeCashEvent(stock.currency, EventType.DIVIDEND, cash, d.at, stockAssetId);
            written++;
        }

        return written;
    }

    /**
     * Auto-ingests bond coupon payments fetched from NBU's depository feed. Coupons
     * dated in the past whose date isn't already represented in the event log get
     * written as DIVIDEND events on the bond's currency cash pile (with
     * {@code incomeSourceAssetId = bondAssetId}).
     *
     * <p>Cash amount = qty held on the coupon's pay-date × per-unit pay value. If the
     * user wasn't holding the bond on that date, the row is skipped — correct, no
     * income was actually paid.
     *
     * <p>Returns the count of new events written. Idempotent on re-run.
     */
    @NonNull
    public Future<Integer> ingestBondCoupons(
            long bondAssetId,
            @NonNull List<DividendIngest> coupons) {
        return executor.submit(() -> ingestBondCouponsSync(bondAssetId, coupons));
    }

    private int ingestBondCouponsSync(long bondAssetId, List<DividendIngest> coupons) {
        AssetEntity bond = assetDao.findById(bondAssetId);
        if (bond == null || bond.type != AssetType.BOND) return 0;

        List<DividendIngest> sorted = new ArrayList<>(coupons);
        sorted.sort(Comparator.comparing(c -> c.at));

        int written = 0;
        LocalDateTime now = LocalDateTime.now();
        for (DividendIngest c : sorted) {
            if (c.at.isAfter(now)) continue;  // future coupon — wait until it pays
            if (c.perShareAmount == null || c.perShareAmount.signum() <= 0) continue;

            LocalDateTime startOfDay = c.at.toLocalDate().atStartOfDay();
            LocalDateTime endExcl = startOfDay.plusDays(1);
            if (eventDao.findDividendOnDate(bondAssetId, startOfDay, endExcl) != null) continue;

            FifoResult fifo = computeFifo(eventDao.getByAssetAsOf(
                    bondAssetId, endOfDay(c.at.toLocalDate())));
            if (fifo.openQty.signum() <= 0) continue;  // didn't hold the bond then

            // UAH OVDPs are tax-exempt by default (defaultBondTaxPct = 0); applyTax is
            // a no-op in that case. Per-asset override still wins for taxable bonds.
            BigDecimal gross = fifo.openQty.multiply(c.perShareAmount);
            BigDecimal cash = applyTax(gross, effectiveTaxRatePct(bond));
            writeCashEvent(bond.currency, EventType.DIVIDEND, cash, c.at, bondAssetId);
            written++;
        }
        return written;
    }

    /**
     * Probe: has this bond already been redeemed (i.e. has a {@code MATURITY} event)?
     * Backs the manual-entry redemption preview's "already redeemed" hint and is the
     * same check {@link #recordBondMaturity} uses for idempotency.
     */
    @NonNull
    public Future<Boolean> hasMaturityFor(long bondAssetId) {
        return executor.submit(() -> eventDao.findMaturityForAsset(bondAssetId) != null);
    }

    /**
     * Records a bond's principal repayment as a two-leg pair, written atomically:
     * <ul>
     *   <li>{@link EventType#OUT} on the bond asset — full open quantity at face value.
     *       FIFO consumes the bond's lots; realized P&amp;L = (face − paid) × qty.</li>
     *   <li>{@link EventType#MATURITY} on the bond's currency cash pile — amount =
     *       openQty × face, with {@code incomeSourceAssetId = bondAssetId}.</li>
     * </ul>
     * Both stamped at 09:00 local on {@code atDate} — same offset as coupons, dodges
     * the noon-trade collision in {@code computeTotalsSync}.
     *
     * <p>Idempotent: skipped if a MATURITY event for this bond already exists.
     * Returns true if a new redemption was written, false if it was a no-op.
     */
    @NonNull
    public Future<Boolean> recordBondMaturity(long bondAssetId, @NonNull LocalDate atDate) {
        return executor.submit(() -> recordBondMaturitySync(bondAssetId, atDate));
    }

    /**
     * Auto-ingest variant for {@link #recordBondMaturity}. Same behavior, separate name
     * so the call sites in the sync worker read cleanly. Idempotent.
     */
    @NonNull
    public Future<Boolean> ingestBondMaturity(long bondAssetId, @NonNull LocalDate atDate) {
        return executor.submit(() -> recordBondMaturitySync(bondAssetId, atDate));
    }

    private boolean recordBondMaturitySync(long bondAssetId, LocalDate atDate) {
        AssetEntity bond = assetDao.findById(bondAssetId);
        if (bond == null || bond.type != AssetType.BOND) {
            throw new IllegalArgumentException(
                    "recordBondMaturity expects a BOND asset id, got " + bondAssetId);
        }
        if (bond.bondInitialPrice == null) {
            throw new IllegalStateException(
                    "Bond " + bond.ticker + " has no face value (bondInitialPrice)");
        }

        // Idempotent: at most one MATURITY per bond ever.
        if (eventDao.findMaturityForAsset(bondAssetId) != null) return false;

        LocalDateTime ts = atDate.atTime(9, 0);
        FifoResult fifo = computeFifo(eventDao.getByAssetAsOf(bondAssetId, ts));
        if (fifo.openQty.signum() <= 0) {
            // Nothing to redeem — bond was already fully sold/matured by hand. Treat as
            // no-op rather than error so the sync worker can keep running on stale data.
            return false;
        }

        BigDecimal face = bond.bondInitialPrice;
        BigDecimal qty = fifo.openQty;
        BigDecimal cashAmount = qty.multiply(face);
        AssetEntity cashAsset = requireCashAsset(bond.currency);

        EventEntity bondLeg = new EventEntity();
        bondLeg.timestamp = ts;
        bondLeg.type = EventType.OUT;
        bondLeg.assetId = bond.id;
        bondLeg.amount = qty;
        bondLeg.price = face;

        EventEntity cashLeg = new EventEntity();
        cashLeg.timestamp = ts;
        cashLeg.type = EventType.MATURITY;
        cashLeg.assetId = cashAsset.id;
        cashLeg.amount = cashAmount;
        cashLeg.price = BigDecimal.ONE;
        cashLeg.incomeSourceAssetId = bondAssetId;

        eventDao.insertTradePair(bondLeg, cashLeg);
        return true;
    }

    /**
     * All bonds with a recorded MATURITY event (i.e. fully redeemed) up to {@code asOf}.
     * Each row aggregates its lifetime cash flows from the event log — no FX conversion,
     * native currency only:
     * <ul>
     *   <li>{@code invested} = Σ purchase IN events × price.</li>
     *   <li>{@code couponsReceived} = Σ DIVIDEND events with {@code incomeSourceAssetId}
     *       pointing back to the bond.</li>
     *   <li>{@code principalReturned} = MATURITY event amount.</li>
     *   <li>{@code realizedPnl} = (couponsReceived + principalReturned) − invested.</li>
     * </ul>
     */
    @NonNull
    public Future<List<MaturedBond>> getMaturedBonds(@NonNull LocalDate asOf) {
        return executor.submit(() -> {
            LocalDateTime upTo = endOfDay(asOf);
            List<MaturedBond> out = new ArrayList<>();
            for (Long bondId : eventDao.findMaturedBondIds()) {
                if (bondId == null) continue;
                AssetEntity bond = assetDao.findById(bondId);
                if (bond == null || bond.type != AssetType.BOND) continue;

                EventEntity maturity = eventDao.findMaturityForAsset(bondId);
                if (maturity == null || maturity.timestamp.isAfter(upTo)) continue;

                BigDecimal invested = BigDecimal.ZERO;
                List<EventEntity> bondEvents = eventDao.getByAssetAsOf(bondId, upTo);
                for (EventEntity e : bondEvents) {
                    if (e.type == EventType.IN) {
                        invested = invested.add(e.amount.multiply(e.price));
                    }
                }

                BigDecimal coupons = BigDecimal.ZERO;
                for (EventEntity e : eventDao.getIncomeFromAssetAsOf(bondId, upTo)) {
                    coupons = coupons.add(e.amount);
                }

                BigDecimal principal = maturity.amount;
                BigDecimal pnl = coupons.add(principal).subtract(invested);

                out.add(new MaturedBond(
                        bond.id,
                        bond.ticker,
                        bond.name,
                        bond.currency,
                        // Redemption-event date — not the bond's contractual maturity,
                        // since manual or off-schedule redemptions may differ.
                        maturity.timestamp.toLocalDate(),
                        invested,
                        coupons,
                        principal,
                        pnl));
            }
            // Most recently matured first so it's near the section header on the screen.
            out.sort((a, b) -> {
                if (a.maturityDate == null && b.maturityDate == null) return 0;
                if (a.maturityDate == null) return 1;
                if (b.maturityDate == null) return -1;
                return b.maturityDate.compareTo(a.maturityDate);
            });
            return out;
        });
    }

    /**
     * Sets a per-asset tax-rate override. Pass {@code null} to clear and fall back to the
     * type default. Forward-only: existing dividend/coupon events are not rewritten — past
     * income was taxed at the rate in force when ingested.
     */
    @NonNull
    public Future<?> setAssetTaxRate(long assetId, @Nullable BigDecimal ratePct) {
        return executor.submit(() -> assetDao.updateTaxRate(assetId, ratePct));
    }

    /**
     * Inserts the asset if no row exists with the same (ticker, currency); returns the id
     * of the existing or newly inserted row. The prototype's id is ignored.
     */
    public Future<Long> findOrCreateAsset(@NonNull AssetEntity prototype) {
        return executor.submit(() -> {
            AssetEntity existing = assetDao.findByTickerAndCurrency(prototype.ticker, prototype.currency);
            if (existing != null) return existing.id;
            return assetDao.insert(prototype);
        });
    }

    // ─── Queries ───────────────────────────────────────────────────────────

    /**
     * All non-cash assets eligible for ongoing actions (trade, manual income, redemption).
     * Bonds with a recorded {@link EventType#MATURITY} are filtered out — once redeemed
     * they can't be traded, can't pay coupons, and can't be redeemed again. They live on
     * in the matured-bonds UI section instead. Ordered by {@code type ASC, ticker ASC}.
     */
    @NonNull
    public Future<List<AssetEntity>> listTradeableAssets() {
        return executor.submit(() -> {
            java.util.Set<Long> maturedIds = new java.util.HashSet<>();
            for (Long id : eventDao.findMaturedBondIds()) {
                if (id != null) maturedIds.add(id);
            }

            List<AssetEntity> stocks = assetDao.findByType(AssetType.STOCK);
            List<AssetEntity> bonds = assetDao.findByType(AssetType.BOND);
            List<AssetEntity> all = new ArrayList<>(stocks.size() + bonds.size());
            for (AssetEntity b : bonds) {  // BOND sorts before STOCK alphabetically
                if (!maturedIds.contains(b.id)) all.add(b);
            }
            all.addAll(stocks);
            return all;
        });
    }

    /**
     * Holdings for every asset as of end-of-day {@code asOf}. Cash piles are always
     * included (even if zero); STOCK/BOND with zero remaining qty are filtered out.
     */
    public Future<List<Holding>> getHoldingsAsOf(@NonNull LocalDate asOf) {
        return executor.submit(() -> computeHoldingsSync(asOf));
    }

    /**
     * Holdings as of {@code windowEnd} paired with windowed P&amp;L (dividends /
     * realized / unrealized / total) computed from per-lot {@link TradeRow}s aggregated
     * by asset. Drives the Portfolio screen when the global filter is active — for
     * {@code FilterPeriod.ALL_TIME} the windowed numbers degenerate to lifetime values.
     *
     * <p>{@code currencyFilter} (when non-null) narrows the result to that single
     * currency; null means "All" — every currency the user holds is included, each
     * row's windowed P&amp;L stays in its asset's native currency. Cash piles always
     * appear with zero windowed P&amp;L (cash has no per-window market dynamic).
     */
    @NonNull
    public Future<List<WindowedHolding>> getWindowedHoldings(
            @Nullable Currency currencyFilter,
            @NonNull LocalDate windowStart,
            @NonNull LocalDate windowEnd) {
        return executor.submit(() ->
                computeWindowedHoldingsSync(currencyFilter, windowStart, windowEnd));
    }

    private List<WindowedHolding> computeWindowedHoldingsSync(
            @Nullable Currency currencyFilter,
            @NonNull LocalDate windowStart,
            @NonNull LocalDate windowEnd) {
        List<Holding> snapshot = computeHoldingsSync(windowEnd);

        // Decide which currencies need a TradeRow pass. For All we hit every currency
        // the user actually holds non-cash assets in; for a specific filter we only
        // need that one. Cash piles never need a TradeRow pass.
        EnumSet<Currency> currenciesToProcess = EnumSet.noneOf(Currency.class);
        if (currencyFilter != null) {
            currenciesToProcess.add(currencyFilter);
        } else {
            for (Holding h : snapshot) {
                if (h.asset.type != AssetType.CASH) {
                    currenciesToProcess.add(h.asset.currency);
                }
            }
        }

        // Per-asset accumulators: [dividends, realized, unrealized, total].
        Map<Long, BigDecimal[]> windowByAsset = new HashMap<>();
        for (Currency c : currenciesToProcess) {
            List<TradeRow> rows = computeTradeRowsSync(c, windowStart, windowEnd);
            for (TradeRow r : rows) {
                BigDecimal[] sums = windowByAsset.computeIfAbsent(r.assetId,
                        k -> new BigDecimal[]{
                                BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, BigDecimal.ZERO });
                sums[0] = sums[0].add(r.windowDividends);
                sums[1] = sums[1].add(r.windowRealizedPnl);
                sums[2] = sums[2].add(r.windowUnrealizedPnl);
                sums[3] = sums[3].add(r.windowTotalPnl);
            }
        }

        BigDecimal[] zeroes = {
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        List<WindowedHolding> out = new ArrayList<>(snapshot.size());
        for (Holding h : snapshot) {
            if (currencyFilter != null && h.asset.currency != currencyFilter) continue;
            BigDecimal[] sums = windowByAsset.getOrDefault(h.asset.id, zeroes);
            out.add(new WindowedHolding(h, sums[0], sums[1], sums[2], sums[3]));
        }
        return out;
    }

    /**
     * Portfolio-wide totals as of {@code asOf}:
     * <ul>
     *   <li>{@code valueInBase} / {@code investedInBase} / {@code pnlInBase} — the
     *       headline numbers, in {@link #BASE_CURRENCY}. Each holding's native market
     *       value is converted via {@code ExchangeRateEntity} on {@code asOf}; each
     *       capital-flow cash event is converted via FX on the <em>event's</em> date
     *       (so FX drift itself shows up as market P&amp;L, by design).</li>
     *   <li>{@code valueByDisplayCurrency}/{@code investedByDisplayCurrency}/
     *       {@code pnlByDisplayCurrency} — the same headline numbers re-expressed in each
     *       Currency for the header ribbon and the user-display-currency picker.</li>
     *   <li>{@code bucketByCurrency} — per-native-currency view with no FX crossing.
     *       Each bucket shows how that currency's assets did in their own terms.</li>
     *   <li>{@code hasFxGaps} — true if any conversion fell back to {@code findOnOrBefore}
     *       and still came up empty. UI shows a subtle hint.</li>
     * </ul>
     */
    @NonNull
    public Future<PortfolioTotals> getPortfolioTotals(@NonNull LocalDate asOf) {
        return executor.submit(() -> computeTotalsSync(asOf));
    }

    /**
     * Coupon and maturity payments touching the {@code [windowFrom, windowTo]} window,
     * combining past payments (event log) with future projections (NBU schedule).
     * Drives the Bonds screen's Expected Payments card and Calendar tab so they react
     * to the global filter — including custom ranges that extend past today.
     *
     * <p>Past portion ({@code windowFrom .. min(windowTo, today)}):
     * <ul>
     *   <li>DIVIDEND events with {@code incomeSourceAssetId = bondId} (coupons paid).</li>
     *   <li>The bond's MATURITY event if its date sits in the window.</li>
     * </ul>
     * Each past row carries {@code paid = true}.
     *
     * <p>Future portion ({@code max(windowFrom, today + 1) .. windowTo}):
     * <ul>
     *   <li>NBU schedule {@code pay_type=1} → coupon, amount = {@code openQty × pay_val}
     *       with {@link #applyTax} applied so the figure matches what
     *       {@link #ingestBondCouponsSync} would credit on the payment day.</li>
     *   <li>NBU schedule {@code pay_type=2} → principal, amount = {@code openQty × face}
     *       (face = {@code asset.bondInitialPrice}). Maturity payouts aren't taxed.</li>
     * </ul>
     * Active bonds only — those with a recorded MATURITY skip the future leg. Each
     * future row carries {@code paid = false}.
     *
     * <p>{@code currencyFilter}, when non-null, narrows to that currency's bonds.
     * {@code totalInBase} converts each row to {@link #BASE_CURRENCY} via FX on the
     * row's date (good-enough for past — exact for paid; for future the date itself
     * is in the future so we use {@code today}'s rate as the available proxy).
     * {@code hasFxGaps} flags rows whose base conversion failed for lack of an FX row.
     *
     * <p>Returns an empty future-side result if {@link #nbuClient} is null (e.g. unit
     * tests), but past-payment rows still come back from the event log.
     */
    @NonNull
    public Future<ExpectedPaymentsResult> getBondPaymentsInWindow(
            @NonNull LocalDate windowFrom,
            @NonNull LocalDate windowTo,
            @NonNull LocalDate today,
            @Nullable Currency currencyFilter) {
        return executor.submit(() -> computeBondPaymentsInWindowSync(
                windowFrom, windowTo, today, currencyFilter));
    }

    /**
     * Past + future coupon/maturity timeline for a single bond — drives the bond
     * detail dialog. Past entries come straight from the event log (DIVIDEND/MATURITY
     * cash events with {@code incomeSourceAssetId = bondId}). Future entries come
     * from NBU's schedule, filtered to dates {@code >= asOf} and computed against
     * the bond's current open quantity. Both lists merged and sorted ascending.
     */
    @NonNull
    public Future<BondTimeline> getBondTimeline(long bondAssetId, @NonNull LocalDate asOf) {
        return executor.submit(() -> computeBondTimelineSync(bondAssetId, asOf));
    }

    private BondTimeline computeBondTimelineSync(long bondAssetId, @NonNull LocalDate asOf) {
        AssetEntity bond = assetDao.findById(bondAssetId);
        List<BondTimelineEntry> entries = new ArrayList<>();
        if (bond == null || bond.type != AssetType.BOND) {
            return new BondTimeline(bond, entries);
        }

        // Past inflow events (DIVIDEND coupons and MATURITY principal).
        for (EventEntity ev : eventDao.getIncomeFromAssetAsOf(bondAssetId, LocalDateTime.now())) {
            if (ev.type != EventType.DIVIDEND && ev.type != EventType.MATURITY) continue;
            entries.add(new BondTimelineEntry(
                    ev.timestamp.toLocalDate(),
                    ev.amount,
                    ev.type,
                    /* paid */ true));
        }

        // Future schedule from NBU. If the bond's already redeemed (MATURITY recorded)
        // there's nothing to project — bail before hitting NBU.
        if (nbuClient != null
                && bond.isin != null && !bond.isin.isBlank()
                && eventDao.findMaturityForAsset(bondAssetId) == null) {

            LocalDateTime upTo = endOfDay(asOf);
            FifoResult fifo = computeFifo(eventDao.getByAssetAsOf(bondAssetId, upTo));
            BigDecimal openQty = fifo.openQty;
            if (openQty.signum() > 0) {
                NbuBondDto dto;
                try {
                    dto = nbuClient.findByIsin(bond.isin);
                } catch (Exception e) {
                    Log.w("PortfolioRepo", "NBU lookup failed for " + bond.ticker, e);
                    dto = null;
                }
                if (dto != null && dto.payments != null) {
                    BigDecimal taxRate = effectiveTaxRatePct(bond);
                    for (NbuBondDto.Payment p : dto.payments) {
                        if (p == null || p.pay_date == null) continue;
                        LocalDate d;
                        try {
                            d = LocalDate.parse(p.pay_date);
                        } catch (Exception ex) {
                            continue;
                        }
                        if (d.isBefore(asOf)) continue;

                        BigDecimal amount;
                        EventType type;
                        if ("1".equals(p.pay_type)) {
                            if (p.pay_val == null) continue;
                            BigDecimal gross = openQty.multiply(
                                    new BigDecimal(p.pay_val.toString()));
                            amount = applyTax(gross, taxRate);
                            type = EventType.DIVIDEND;
                        } else if ("2".equals(p.pay_type)) {
                            if (bond.bondInitialPrice == null) continue;
                            amount = openQty.multiply(bond.bondInitialPrice);
                            type = EventType.MATURITY;
                        } else {
                            continue;
                        }
                        entries.add(new BondTimelineEntry(d, amount, type, /* paid */ false));
                    }
                }
            }
        }

        entries.sort(Comparator.comparing((BondTimelineEntry e) -> e.date));
        return new BondTimeline(bond, entries);
    }

    private ExpectedPaymentsResult computeBondPaymentsInWindowSync(
            @NonNull LocalDate windowFrom,
            @NonNull LocalDate windowTo,
            @NonNull LocalDate today,
            @Nullable Currency currencyFilter) {
        BondPaymentsAccumulator acc = new BondPaymentsAccumulator();

        // Past portion: window clamped at today (inclusive). hasPast is false when
        // the whole window sits in the future (e.g. custom 2027..2028).
        LocalDate pastUpTo = windowTo.isBefore(today) ? windowTo : today;
        boolean hasPast = !pastUpTo.isBefore(windowFrom);
        // Future portion: starts the day after today and ends at windowTo. hasFuture
        // is false when the window ends on or before today (purely past view).
        boolean hasFuture = windowTo.isAfter(today);

        for (AssetEntity asset : assetDao.findByType(AssetType.BOND)) {
            if (currencyFilter != null && asset.currency != currencyFilter) continue;

            // ── Past payments from the event log ──────────────────────────
            if (hasPast) {
                LocalDateTime pastUpToLdt = endOfDay(pastUpTo);
                for (EventEntity ev : eventDao.getIncomeFromAssetAsOf(asset.id, pastUpToLdt)) {
                    LocalDate d = ev.timestamp.toLocalDate();
                    if (d.isBefore(windowFrom) || d.isAfter(pastUpTo)) continue;
                    acc.add(asset, d, ev.amount, EventType.DIVIDEND, /* paid */ true, d);
                }
                EventEntity maturity = eventDao.findMaturityForAsset(asset.id);
                if (maturity != null) {
                    LocalDate d = maturity.timestamp.toLocalDate();
                    if (!d.isBefore(windowFrom) && !d.isAfter(pastUpTo)) {
                        acc.add(asset, d, maturity.amount, EventType.MATURITY, /* paid */ true, d);
                    }
                }
            }

            // ── Future projections from NBU schedule ──────────────────────
            // Skip when there's no future to project, when NBU isn't wired (tests),
            // when the bond has no ISIN, or when it's already redeemed.
            if (!hasFuture) continue;
            if (nbuClient == null) continue;
            if (asset.isin == null || asset.isin.isBlank()) continue;
            if (eventDao.findMaturityForAsset(asset.id) != null) continue;

            List<EventEntity> events = eventDao.getByAssetAsOf(asset.id, endOfDay(today));
            FifoResult fifo = computeFifo(events);
            BigDecimal openQty = fifo.openQty;
            if (openQty.signum() <= 0) continue;

            NbuBondDto dto;
            try {
                dto = nbuClient.findByIsin(asset.isin);
            } catch (Exception e) {
                Log.w("PortfolioRepo", "NBU lookup failed for " + asset.ticker, e);
                continue;
            }
            if (dto == null || dto.payments == null) continue;

            BigDecimal taxRate = effectiveTaxRatePct(asset);
            for (NbuBondDto.Payment p : dto.payments) {
                if (p == null || p.pay_date == null) continue;
                LocalDate d;
                try {
                    d = LocalDate.parse(p.pay_date);
                } catch (Exception ex) {
                    continue;
                }
                // Strictly future, and inside the window.
                if (!d.isAfter(today)) continue;
                if (d.isBefore(windowFrom) || d.isAfter(windowTo)) continue;

                BigDecimal amount;
                EventType type;
                if ("1".equals(p.pay_type)) {
                    if (p.pay_val == null) continue;
                    BigDecimal gross = openQty.multiply(new BigDecimal(p.pay_val.toString()));
                    amount = applyTax(gross, taxRate);
                    type = EventType.DIVIDEND;
                } else if ("2".equals(p.pay_type)) {
                    if (asset.bondInitialPrice == null) continue;
                    amount = openQty.multiply(asset.bondInitialPrice);
                    type = EventType.MATURITY;
                } else {
                    continue;
                }

                // Future FX is unknowable — fall back to today's rate as the proxy
                // (same convention the previous future-only path used).
                acc.add(asset, d, amount, type, /* paid */ false, today);
            }
        }

        return acc.build();
    }

    /**
     * Accumulates bond payment rows + the four cross-cuts the UI needs from
     * {@link ExpectedPaymentsResult}: per-native-currency, per-display-currency
     * (for the equivalents ribbon), and the BASE_CURRENCY rollup, alongside an
     * fx-gap flag. Local helper to keep
     * {@link #computeBondPaymentsInWindowSync} flat and readable.
     */
    private final class BondPaymentsAccumulator {
        final List<ExpectedPayment> rows = new ArrayList<>();
        final Map<Currency, BigDecimal> totalsByCurrency = new EnumMap<>(Currency.class);
        final Map<Currency, BigDecimal> totalsByDisplayCurrency = new EnumMap<>(Currency.class);
        BigDecimal totalInBase = BigDecimal.ZERO;
        boolean hasFxGaps = false;

        BondPaymentsAccumulator() {
            for (Currency c : Currency.values()) {
                totalsByDisplayCurrency.put(c, BigDecimal.ZERO);
            }
        }

        /** {@code fxOn} is the FX-rate date — actual payment date for paid rows,
         *  today for projections (tomorrow's rates aren't knowable). */
        void add(
                @NonNull AssetEntity asset,
                @NonNull LocalDate date,
                @NonNull BigDecimal amount,
                @NonNull EventType type,
                boolean paid,
                @NonNull LocalDate fxOn) {
            rows.add(new ExpectedPayment(
                    asset.id, asset.ticker, asset.currency, date, amount, type, paid));
            totalsByCurrency.merge(asset.currency, amount, BigDecimal::add);

            for (Currency display : Currency.values()) {
                BigDecimal inDisplay = (display == asset.currency)
                        ? amount
                        : convert(amount, asset.currency, display, fxOn);
                if (inDisplay == null) {
                    hasFxGaps = true;
                } else {
                    totalsByDisplayCurrency.merge(display, inDisplay, BigDecimal::add);
                }
            }
            BigDecimal inBase = convert(amount, asset.currency, BASE_CURRENCY, fxOn);
            if (inBase == null) {
                hasFxGaps = true;
            } else {
                totalInBase = totalInBase.add(inBase);
            }
        }

        @NonNull
        ExpectedPaymentsResult build() {
            rows.sort(Comparator.comparing((ExpectedPayment ep) -> ep.date)
                    .thenComparing(ep -> ep.ticker));
            return new ExpectedPaymentsResult(
                    rows, totalsByCurrency, totalsByDisplayCurrency,
                    totalInBase, BASE_CURRENCY, hasFxGaps);
        }
    }

    /**
     * Three-axis breakdown of the portfolio's current value, all converted to
     * {@code displayCurrency} so slice ratios are comparable. Drives the analytics-pie
     * tab. Slices with non-positive values are filtered out — pie charts can't render
     * negative slices anyway, and zero-value entries just clutter the legend.
     *
     * <p>{@code hasFxGaps} flags any holding whose native currency couldn't be converted
     * (no FX row on or before {@code asOf}); those holdings are excluded from the slices,
     * so pies can under-represent the portfolio when the flag is true. Surface that to
     * the user the same way the chart does.
     */
    @NonNull
    public Future<AnalyticsBreakdown> getAnalyticsAsOf(
            @NonNull LocalDate asOf, @NonNull Currency displayCurrency) {
        return getAnalyticsAsOf(asOf, displayCurrency, null);
    }

    /**
     * Variant with an optional {@code currencyFilter}. When non-null, only holdings
     * in that currency are included — used by the global filter so picking a
     * specific currency on Portfolio narrows the Analytics pies to that bucket.
     */
    @NonNull
    public Future<AnalyticsBreakdown> getAnalyticsAsOf(
            @NonNull LocalDate asOf,
            @NonNull Currency displayCurrency,
            @Nullable Currency currencyFilter) {
        return executor.submit(() -> {
            List<Holding> holdings = computeHoldingsSync(asOf);

            // Cash piles are included in computeHoldingsSync regardless of balance —
            // skip the truly zero ones so the pies aren't littered with empty slices.
            Map<AssetType, BigDecimal> typeMap = new EnumMap<>(AssetType.class);
            Map<Currency, BigDecimal> currencyMap = new EnumMap<>(Currency.class);
            List<AssetSlice> assetSlices = new ArrayList<>(holdings.size());
            boolean hasFxGaps = false;

            for (Holding h : holdings) {
                if (h.marketValue == null || h.marketValue.signum() == 0) continue;
                if (currencyFilter != null && h.asset.currency != currencyFilter) continue;

                BigDecimal inDisplay = convert(
                        h.marketValue, h.asset.currency, displayCurrency, asOf);
                if (inDisplay == null) {
                    hasFxGaps = true;
                    continue;
                }
                if (inDisplay.signum() <= 0) continue;

                typeMap.merge(h.asset.type, inDisplay, BigDecimal::add);
                currencyMap.merge(h.asset.currency, inDisplay, BigDecimal::add);
                assetSlices.add(new AssetSlice(
                        h.asset.id, h.asset.ticker, h.asset.type, h.asset.currency, inDisplay));
            }

            List<Slice> byType = new ArrayList<>(typeMap.size());
            for (Map.Entry<AssetType, BigDecimal> e : typeMap.entrySet()) {
                byType.add(new Slice(e.getKey().name(), e.getValue()));
            }
            List<Slice> byCurrency = new ArrayList<>(currencyMap.size());
            for (Map.Entry<Currency, BigDecimal> e : currencyMap.entrySet()) {
                byCurrency.add(new Slice(e.getKey().name(), e.getValue()));
            }

            // Largest slices first — easier to read in the legend.
            byType.sort((a, b) -> b.value.compareTo(a.value));
            byCurrency.sort((a, b) -> b.value.compareTo(a.value));
            List<Slice> byAsset = new ArrayList<>(assetSlices.size());
            assetSlices.sort((a, b) -> b.value.compareTo(a.value));
            for (AssetSlice a : assetSlices) {
                byAsset.add(new Slice(a.ticker, a.value));
            }

            return new AnalyticsBreakdown(displayCurrency, byType, byCurrency, byAsset, hasFxGaps);
        });
    }

    /**
     * Returns stored daily snapshots in {@code [from, to]}, ascending by date. The chart
     * ViewModel appends a live right-edge point for today via {@link #getPortfolioTotals}.
     */
    @NonNull
    public Future<List<PortfolioValueSnapshotEntity>> getSnapshots(
            @NonNull LocalDate from, @NonNull LocalDate to) {
        return executor.submit(() -> portfolioValueDao.getRange(from, to));
    }

    /**
     * Stored snapshots in {@code [from, to]} re-expressed in {@code displayCurrency} via
     * stored FX rates (using {@code findOnOrBefore} per snapshot date). Snapshots are
     * always written in {@link #BASE_CURRENCY}; this method is the render-layer
     * conversion path for the chart's display-currency picker.
     *
     * <p>If no FX rate is on-or-before a snapshot's date, that point is still returned
     * with {@code hasFxGaps=true} and the original BASE-currency numbers — the chart
     * marker visually flags the gap and the line keeps continuity (better than dropping
     * the point and leaving a hole).
     */
    /**
     * Stored snapshots in {@code [from, to]} sliced to a single native {@code currency}
     * — drives the per-currency charts on the breakdown pages. No FX crossing, so
     * {@code hasFxGaps} is always false in the returned points (the FX gap flag on the
     * row only matters for the base-currency view).
     */
    @NonNull
    public Future<List<ConvertedSnapshot>> getSnapshotsForCurrency(
            @NonNull LocalDate from, @NonNull LocalDate to, @NonNull Currency currency) {
        return executor.submit(() -> {
            List<PortfolioValueSnapshotEntity> rows = portfolioValueDao.getRange(from, to);
            List<ConvertedSnapshot> out = new ArrayList<>(rows.size());
            for (PortfolioValueSnapshotEntity s : rows) {
                BigDecimal v;
                BigDecimal i;
                switch (currency) {
                    case USD: v = s.valueUsd; i = s.investedUsd; break;
                    case EUR: v = s.valueEur; i = s.investedEur; break;
                    case UAH: v = s.valueUah; i = s.investedUah; break;
                    default: throw new IllegalStateException("Unknown currency " + currency);
                }
                out.add(new ConvertedSnapshot(s.date, v, i, false));
            }
            return out;
        });
    }

    @NonNull
    public Future<List<ConvertedSnapshot>> getSnapshotsInDisplay(
            @NonNull LocalDate from, @NonNull LocalDate to, @NonNull Currency displayCurrency) {
        return executor.submit(() -> {
            List<PortfolioValueSnapshotEntity> rows = portfolioValueDao.getRange(from, to);
            List<ConvertedSnapshot> out = new ArrayList<>(rows.size());
            for (PortfolioValueSnapshotEntity s : rows) {
                BigDecimal v = convert(s.valueInBase, s.baseCurrency, displayCurrency, s.date);
                BigDecimal i = convert(s.investedInBase, s.baseCurrency, displayCurrency, s.date);
                boolean gap = s.hasFxGaps || v == null || i == null;
                out.add(new ConvertedSnapshot(
                        s.date,
                        v != null ? v : s.valueInBase,
                        i != null ? i : s.investedInBase,
                        gap));
            }
            return out;
        });
    }

    /**
     * Per-lot P&amp;L rows for one currency, over the window {@code [windowStart, windowEnd]}.
     * One row per original IN event (lot model #3): shows remaining qty plus realized +
     * unrealized P&amp;L evaluated over the window.
     *
     * <p>Window semantics (option 2 — realized + mark-to-market):
     * <ul>
     *   <li>For a lot purchased <em>before</em> {@code windowStart}: baseline per-unit value
     *       is the asset's price on {@code windowStart} (window-start mark). Realized =
     *       Σ q × (sell_price − baseline) for sells in-window. Unrealized = remaining_qty ×
     *       (end_price − baseline).</li>
     *   <li>For a lot purchased <em>within</em> the window: baseline is the lot's own
     *       purchase price. Realized = Σ q × (sell_price − purchase_price). Unrealized =
     *       remaining_qty × (end_price − purchase_price). Matches "total P&amp;L since
     *       purchase" for the All-time filter.</li>
     * </ul>
     * Lots fully closed before the window, with no activity in-window, are omitted.
     *
     * <p>Bond per-lot valuation uses {@link BondValuator} on a synthetic single-lot list
     * (coupon attribution is ambiguous for multi-lot bonds; dev data has 1 lot per bond).
     */
    @NonNull
    public Future<List<TradeRow>> getTradeRows(
            @NonNull Currency currency,
            @NonNull LocalDate windowStart,
            @NonNull LocalDate windowEnd) {
        return executor.submit(() -> computeTradeRowsSync(currency, windowStart, windowEnd));
    }

    private List<TradeRow> computeTradeRowsSync(
            Currency currency, LocalDate windowStart, LocalDate windowEnd) {
        LocalDateTime winEndDT = endOfDay(windowEnd);
        List<TradeRow> out = new ArrayList<>();

        for (AssetEntity asset : assetDao.getAll()) {
            if (asset.currency != currency) continue;
            if (asset.type == AssetType.CASH) continue;

            List<EventEntity> events = eventDao.getByAssetAsOf(asset.id, winEndDT);
            List<LotTimeline> lots = buildLotTimelines(events);

            // Per-lot dividend attribution. For every income event (DIVIDEND/MATURITY)
            // tagged with this asset as source within the window, split its amount across
            // the lots open at that moment by qty share. Returns map keyed by IN-event id.
            // The previous lifetime-attribution maps (at window-start and window-end)
            // were only needed by the synthetic bond accrual formula — gone now that
            // bonds value at face × qty.
            List<EventEntity> income = eventDao.getIncomeFromAssetAsOf(asset.id, winEndDT);
            Map<Long, BigDecimal> dividendsByLotId = attributeIncomeToLots(
                    events, income, windowStart, windowEnd);

            for (LotTimeline lot : lots) {
                BigDecimal lotDivs = dividendsByLotId.getOrDefault(lot.inEvent.id, BigDecimal.ZERO);
                TradeRow row = computeRowForLot(asset, lot, windowStart, windowEnd, lotDivs);
                if (row != null) out.add(row);
            }
        }

        out.sort(Comparator.comparing((TradeRow r) -> r.purchasedAt));
        return out;
    }

    /**
     * Walks the asset's own events + income events tagged from it, in a single
     * chronological stream, and distributes each in-window income event across the
     * lots open at that moment by qty pro-rata. At equal timestamps we process
     * IN/SPLIT first, then INCOME, then OUT — so the final coupon paid alongside a
     * bond's redemption sees the pre-OUT qty (the user holds the bond at the moment
     * of payment, redemption happens after).
     */
    @NonNull
    private Map<Long, BigDecimal> attributeIncomeToLots(
            @NonNull List<EventEntity> bondEvents,
            @NonNull List<EventEntity> incomeEvents,
            @NonNull LocalDate windowStart,
            @NonNull LocalDate windowEnd) {
        Map<Long, BigDecimal> attribution = new HashMap<>();
        if (incomeEvents.isEmpty()) return attribution;

        LocalDateTime winStart = windowStart.atStartOfDay();
        LocalDateTime winEnd = endOfDay(windowEnd);

        // Merge with sort key (timestamp, prio). Lower prio runs first at ties.
        // 0 = IN/SPLIT (qty change before income), 1 = income, 2 = OUT.
        List<MergedEvent> merged = new ArrayList<>(bondEvents.size() + incomeEvents.size());
        for (EventEntity e : bondEvents) {
            int prio;
            if (e.type == EventType.IN || e.type == EventType.SPLIT) prio = 0;
            else if (e.type == EventType.OUT) prio = 2;
            else continue;
            merged.add(new MergedEvent(e, prio, false));
        }
        for (EventEntity e : incomeEvents) {
            merged.add(new MergedEvent(e, 1, true));
        }
        merged.sort(Comparator
                .comparing((MergedEvent m) -> m.event.timestamp)
                .thenComparingInt(m -> m.prio)
                .thenComparingLong(m -> m.event.id));

        // Lot map is insertion-ordered so OUT consumes oldest-first (FIFO match with
        // computeFifo / buildLotTimelines).
        java.util.LinkedHashMap<Long, BigDecimal> openByLotId = new java.util.LinkedHashMap<>();

        for (MergedEvent m : merged) {
            EventEntity e = m.event;
            if (m.income) {
                // Outside-window income still affects the running map's qty (it doesn't,
                // actually — income on cash doesn't move bond qty), but we still need to
                // skip attributing for events outside the window.
                if (e.timestamp.isBefore(winStart) || e.timestamp.isAfter(winEnd)) continue;
                BigDecimal totalOpen = BigDecimal.ZERO;
                for (BigDecimal v : openByLotId.values()) totalOpen = totalOpen.add(v);
                if (totalOpen.signum() <= 0) continue;
                for (Map.Entry<Long, BigDecimal> entry : openByLotId.entrySet()) {
                    BigDecimal share = e.amount.multiply(entry.getValue()).divide(totalOpen, MC);
                    attribution.merge(entry.getKey(), share, BigDecimal::add);
                }
            } else if (e.type == EventType.IN) {
                openByLotId.put(e.id, e.amount);
            } else if (e.type == EventType.OUT) {
                BigDecimal remaining = e.amount;
                java.util.Iterator<Map.Entry<Long, BigDecimal>> it = openByLotId.entrySet().iterator();
                while (remaining.signum() > 0 && it.hasNext()) {
                    Map.Entry<Long, BigDecimal> head = it.next();
                    BigDecimal headQty = head.getValue();
                    BigDecimal consume = headQty.min(remaining);
                    BigDecimal newQty = headQty.subtract(consume);
                    remaining = remaining.subtract(consume);
                    if (newQty.signum() == 0) it.remove();
                    else head.setValue(newQty);
                }
            } else if (e.type == EventType.SPLIT) {
                BigDecimal ratio = e.amount;
                if (ratio.signum() <= 0) continue;
                openByLotId.replaceAll((k, v) -> v.multiply(ratio));
            }
        }

        return attribution;
    }

    private static final class MergedEvent {
        final EventEntity event;
        final int prio;
        final boolean income;
        MergedEvent(EventEntity event, int prio, boolean income) {
            this.event = event;
            this.prio = prio;
            this.income = income;
        }
    }

    @Nullable
    private TradeRow computeRowForLot(
            AssetEntity asset, LotTimeline lot, LocalDate windowStart, LocalDate windowEnd,
            @NonNull BigDecimal windowDividends) {
        LocalDateTime lotAcquiredAt = lot.inEvent.timestamp;
        LocalDate lotDate = lotAcquiredAt.toLocalDate();
        BigDecimal lotPrice = lot.inEvent.price;
        BigDecimal origQty = lot.inEvent.amount;
        boolean lotInWindow = !lotDate.isBefore(windowStart);

        BigDecimal consumedBefore = BigDecimal.ZERO;
        BigDecimal consumedInWindow = BigDecimal.ZERO;
        for (Consumption c : lot.consumptions) {
            if (c.consumedAt.toLocalDate().isBefore(windowStart)) {
                consumedBefore = consumedBefore.add(c.qty);
            } else {
                consumedInWindow = consumedInWindow.add(c.qty);
            }
        }
        BigDecimal remainingQtyE = origQty.subtract(consumedBefore).subtract(consumedInWindow);

        // Skip: lot fully closed before window AND no sells in window AND lot not purchased in window.
        if (remainingQtyE.signum() == 0 && consumedInWindow.signum() == 0 && !lotInWindow) {
            return null;
        }

        // Realized P&L: lifetime-anchored to the lot's purchase price for every sell or
        // maturity in the window. This matches brokerage statements — a bond bought in
        // 2023 and matured in 2025 reports its full premium loss in 2025's view, not
        // split with an earlier "pre-window unrealized" bucket the user can't see.
        BigDecimal realized = BigDecimal.ZERO;
        for (Consumption c : lot.consumptions) {
            if (c.consumedAt.toLocalDate().isBefore(windowStart)) continue;
            realized = realized.add(c.qty.multiply(c.sellPrice.subtract(lotPrice)));
        }

        // Unrealized: window-anchored mark-to-market for any qty still open at window end.
        // Baseline is the lot's purchase price for in-window lots, the window-start mark
        // otherwise. For BOND the mark is constant at face — so a pre-window bond holds
        // unrealized = 0 in the window, while an in-window bond purchase shows
        // (face − purchase) × qty = −premium (constant from purchase forward).
        BigDecimal baselineUnit = lotInWindow
                ? lotPrice
                : perUnitValueAt(asset, windowStart, lotAcquiredAt, origQty);
        if (baselineUnit == null) baselineUnit = lotPrice;

        BigDecimal unrealized = BigDecimal.ZERO;
        if (remainingQtyE.signum() > 0) {
            BigDecimal endUnit = perUnitValueAt(asset, windowEnd, lotAcquiredAt, origQty);
            if (endUnit != null) {
                unrealized = remainingQtyE.multiply(endUnit.subtract(baselineUnit));
            }
        }

        BigDecimal total = realized.add(unrealized).add(windowDividends);
        return new TradeRow(
                asset.id, asset.ticker, asset.type, asset.currency,
                lotAcquiredAt, origQty, remainingQtyE, lotPrice,
                realized, unrealized, windowDividends, total);
    }

    /**
     * Per-unit value of {@code asset} on {@code date}. STOCK uses the close on-or-before;
     * BOND uses {@link AssetEntity#bondInitialPrice} (face) — bonds have no real
     * mid-life market price in this app, and the broker-aligned model holds them at
     * face during the lifetime; premium/discount surfaces as cost-vs-face P&amp;L.
     * Returns null only when STOCK has no price on-or-before the date (never-synced ticker).
     */
    @Nullable
    private BigDecimal perUnitValueAt(
            AssetEntity asset, LocalDate date, LocalDateTime lotAcquiredAt, BigDecimal origQty) {
        if (asset.type == AssetType.STOCK) {
            StockPriceEntity q = stockPriceDao.findOnOrBefore(asset.ticker, date);
            return q == null ? null : q.closePrice;
        }
        if (asset.type == AssetType.BOND) {
            return asset.bondInitialPrice;
        }
        return null;
    }

    private PortfolioTotals computeTotalsSync(LocalDate asOf) {
        List<Holding> holdings = computeHoldingsSync(asOf);
        LocalDateTime upTo = endOfDay(asOf);

        Map<Currency, BigDecimal> valueBucket = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> investedBucket = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> dividendsBucket = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> realizedBucket = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> unrealizedBucket = new EnumMap<>(Currency.class);

        boolean hasFxGaps = false;

        // Native-currency value bucket — sum market values of holdings whose native
        // currency matches the bucket. Works for cash too (cash.marketValue == balance).
        for (Holding h : holdings) {
            if (h.marketValue == null) continue;
            valueBucket.merge(h.asset.currency, h.marketValue, BigDecimal::add);
        }

        // Value in base: convert each native value via FX(native → base) on asOf.
        BigDecimal valueInBase = BigDecimal.ZERO;
        for (Map.Entry<Currency, BigDecimal> e : valueBucket.entrySet()) {
            BigDecimal converted = convert(e.getValue(), e.getKey(), BASE_CURRENCY, asOf);
            if (converted == null) { hasFxGaps = true; continue; }
            valueInBase = valueInBase.add(converted);
        }

        // Invested + dividends per currency. One walk over each cash pile's events:
        //   - DIVIDEND  → income (counted as P&L, not capital)
        //   - paired with a non-cash event at same timestamp → trade leg, skip
        //   - else → external capital deposit/withdrawal, signed by IN/OUT
        BigDecimal investedInBase = BigDecimal.ZERO;
        for (AssetEntity cashAsset : assetDao.findByType(AssetType.CASH)) {
            BigDecimal capital = BigDecimal.ZERO;
            BigDecimal dividends = BigDecimal.ZERO;
            List<EventEntity> events = eventDao.getByAssetAsOf(cashAsset.id, upTo);
            for (EventEntity ev : events) {
                if (ev.type == EventType.DIVIDEND) {
                    dividends = dividends.add(ev.amount);
                    continue;
                }

                // Currency conversions count as per-currency capital movement (the
                // user did move money into / out of this currency). They bypass the
                // trade-leg detection — a conversion can share a timestamp with a
                // stock trade and shouldn't be misclassified.
                if (ev.type == EventType.CONVERSION_IN) {
                    capital = capital.add(ev.amount);
                    BigDecimal base = convert(
                            ev.amount, cashAsset.currency, BASE_CURRENCY, ev.timestamp.toLocalDate());
                    if (base == null) hasFxGaps = true;
                    else investedInBase = investedInBase.add(base);
                    continue;
                }
                if (ev.type == EventType.CONVERSION_OUT) {
                    capital = capital.subtract(ev.amount);
                    BigDecimal base = convert(
                            ev.amount, cashAsset.currency, BASE_CURRENCY, ev.timestamp.toLocalDate());
                    if (base == null) hasFxGaps = true;
                    else investedInBase = investedInBase.subtract(base);
                    continue;
                }

                if (ev.incomeSourceAssetId != null) continue;  // legacy income rows, defensive
                if (eventDao.countNonCashEventsAt(ev.timestamp) > 0) continue;  // trade leg
                BigDecimal signed = (ev.type == EventType.IN) ? ev.amount : ev.amount.negate();
                capital = capital.add(signed);
                // Base-currency conversion uses FX at each deposit's own date so FX drift
                // after the deposit shows up as market P&L (by design).
                BigDecimal baseContribution = convert(
                        signed, cashAsset.currency, BASE_CURRENCY, ev.timestamp.toLocalDate());
                if (baseContribution == null) {
                    hasFxGaps = true;
                } else {
                    investedInBase = investedInBase.add(baseContribution);
                }
            }
            investedBucket.put(cashAsset.currency, capital);
            dividendsBucket.put(cashAsset.currency, dividends);
        }

        // Realized + unrealized P&L per currency. Walks every non-cash asset once:
        //   realized   = Σ (sell_proceeds − matched_lot_cost) over closed lots
        //   unrealized = (current_market_value − open_cost_basis) over open lots
        // Identity (per currency, no FX):  pnl = dividends + realized + unrealized.
        for (AssetEntity asset : assetDao.getAll()) {
            if (asset.type == AssetType.CASH) continue;
            List<EventEntity> evs = eventDao.getByAssetAsOf(asset.id, upTo);
            FifoResult fifo = computeFifo(evs);

            BigDecimal realized = fifo.realizedProceeds.subtract(fifo.realizedCostBasis);
            realizedBucket.merge(asset.currency, realized, BigDecimal::add);

            if (fifo.openQty.signum() > 0) {
                BigDecimal mv = computeMarketValue(asset, fifo.openLots, upTo);
                if (mv != null) {
                    unrealizedBucket.merge(
                            asset.currency, mv.subtract(fifo.openCostBasis), BigDecimal::add);
                }
            }
        }

        // Per-currency bucket. {@code value - invested} stays the canonical P&L; the
        // breakdown fields decompose it into dividends / realized / unrealized.
        Map<Currency, NativeBucket> bucketByCurrency = new EnumMap<>(Currency.class);
        for (Currency c : Currency.values()) {
            BigDecimal v = valueBucket.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal i = investedBucket.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal d = dividendsBucket.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal r = realizedBucket.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal u = unrealizedBucket.getOrDefault(c, BigDecimal.ZERO);
            bucketByCurrency.put(c, new NativeBucket(v, i, v.subtract(i), d, r, u));
        }

        // Display ribbon — same value/invested/pnl re-expressed in each Currency. Conversion
        // is purely render-layer (UTC/timezone analogy): the underlying snapshot stays in
        // BASE_CURRENCY; this map lets the UI pick which one gets the headline number.
        Map<Currency, BigDecimal> valueByDisplayCurrency = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> investedByDisplayCurrency = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> pnlByDisplayCurrency = new EnumMap<>(Currency.class);
        valueByDisplayCurrency.put(BASE_CURRENCY, valueInBase);
        investedByDisplayCurrency.put(BASE_CURRENCY, investedInBase);
        pnlByDisplayCurrency.put(BASE_CURRENCY, valueInBase.subtract(investedInBase));
        for (Currency c : Currency.values()) {
            if (c == BASE_CURRENCY) continue;
            BigDecimal v = convert(valueInBase, BASE_CURRENCY, c, asOf);
            BigDecimal i = convert(investedInBase, BASE_CURRENCY, c, asOf);
            if (v == null || i == null) { hasFxGaps = true; continue; }
            valueByDisplayCurrency.put(c, v);
            investedByDisplayCurrency.put(c, i);
            pnlByDisplayCurrency.put(c, v.subtract(i));
        }

        return new PortfolioTotals(
                BASE_CURRENCY,
                valueInBase,
                investedInBase,
                valueInBase.subtract(investedInBase),
                valueByDisplayCurrency,
                investedByDisplayCurrency,
                pnlByDisplayCurrency,
                bucketByCurrency,
                hasFxGaps);
    }

    /**
     * Batch-rebuilds portfolio snapshots for {@code [from, to]}. Loads all events,
     * prices, and FX rates once at the start, then computes each day's snapshot from
     * in-memory state — eliminates the per-day DAO round-trips that made the per-day
     * loop slow down with history depth.
     *
     * <p>Mirrors {@link #computeTotalsSync} exactly; the two MUST stay in sync. The
     * single-call variant is kept for ad-hoc UI queries (Holdings header, Chart's
     * right-edge live point).
     *
     * <p>Progress is reported via {@code cb} once per day. Per-day failures are logged
     * and swallowed so one bad day doesn't abort the whole rebuild.
     */
    @NonNull
    public Future<Integer> rebuildSnapshotsBatch(
            @NonNull LocalDate from,
            @NonNull LocalDate to,
            @NonNull BatchSnapshotProgress cb) {
        return executor.submit(() -> rebuildSnapshotsBatchSync(from, to, cb));
    }

    @FunctionalInterface
    public interface BatchSnapshotProgress {
        void onProgress(int current, int total, @NonNull String label);
    }

    private int rebuildSnapshotsBatchSync(
            LocalDate from, LocalDate to, BatchSnapshotProgress cb) {
        BulkSnapshotData bulk = loadBulkSnapshotData();
        int totalDays = (int) (to.toEpochDay() - from.toEpochDay() + 1);
        if (totalDays <= 0) return 0;
        int idx = 0;
        int written = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            idx++;
            cb.onProgress(idx, totalDays, d.toString());
            try {
                PortfolioTotals t = computeTotalsFromBulk(d, bulk);
                portfolioValueDao.upsert(buildSnapshotEntity(d, t));
                written++;
            } catch (Exception e) {
                Log.w("PortfolioRepository", "snapshot failed for " + d, e);
            }
        }
        return written;
    }

    /** All the DB tables a snapshot computation needs, pre-loaded into in-memory
     *  structures shaped for the lookups {@code computeTotalsSync} performs. */
    private static final class BulkSnapshotData {
        List<AssetEntity> allAssets;
        // Events grouped by assetId, each list sorted by timestamp ASC.
        Map<Long, List<EventEntity>> eventsByAssetId;
        // DIVIDEND/MATURITY events keyed by their incomeSourceAssetId, sorted by ts.
        Map<Long, List<EventEntity>> incomeBySourceAssetId;
        // Set of timestamps where ANY non-cash event occurs — used by trade-leg detection.
        Set<LocalDateTime> nonCashTimestamps;
        // Stock close prices: ticker → (date → close), navigable for findOnOrBefore.
        Map<String, NavigableMap<LocalDate, BigDecimal>> pricesByTicker;
        // FX rates: (src,tgt) → (date → rate).
        Map<Currency, Map<Currency, NavigableMap<LocalDate, BigDecimal>>> fxBySrcTgt;
    }

    private BulkSnapshotData loadBulkSnapshotData() {
        BulkSnapshotData b = new BulkSnapshotData();
        b.allAssets = assetDao.getAll();
        b.eventsByAssetId = new HashMap<>();
        b.incomeBySourceAssetId = new HashMap<>();
        b.nonCashTimestamps = new HashSet<>();

        Set<Long> cashAssetIds = new HashSet<>();
        for (AssetEntity a : b.allAssets) {
            if (a.type == AssetType.CASH) cashAssetIds.add(a.id);
        }

        for (EventEntity e : eventDao.getAllChronological()) {
            b.eventsByAssetId.computeIfAbsent(e.assetId, k -> new ArrayList<>()).add(e);
            if (e.incomeSourceAssetId != null) {
                b.incomeBySourceAssetId
                        .computeIfAbsent(e.incomeSourceAssetId, k -> new ArrayList<>())
                        .add(e);
            }
            if (!cashAssetIds.contains(e.assetId)) {
                b.nonCashTimestamps.add(e.timestamp);
            }
        }

        b.pricesByTicker = new HashMap<>();
        for (StockPriceEntity p : stockPriceDao.getAll()) {
            b.pricesByTicker
                    .computeIfAbsent(p.ticker, k -> new TreeMap<>())
                    .put(p.date, p.closePrice);
        }

        b.fxBySrcTgt = new EnumMap<>(Currency.class);
        for (ExchangeRateEntity r : exchangeRateDao.getAll()) {
            b.fxBySrcTgt
                    .computeIfAbsent(r.sourceCurrency, k -> new EnumMap<>(Currency.class))
                    .computeIfAbsent(r.targetCurrency, k -> new TreeMap<>())
                    .put(r.date, r.rate);
        }

        return b;
    }

    /** {@link #computeTotalsSync} re-implemented against pre-loaded {@link BulkSnapshotData}.
     *  Logic must mirror that method exactly — only the DAO calls are replaced with
     *  in-memory lookups. */
    private PortfolioTotals computeTotalsFromBulk(LocalDate asOf, BulkSnapshotData b) {
        LocalDateTime upTo = endOfDay(asOf);

        Map<Currency, BigDecimal> valueBucket = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> investedBucket = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> dividendsBucket = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> realizedBucket = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> unrealizedBucket = new EnumMap<>(Currency.class);

        boolean hasFxGaps = false;

        // Holdings: market value per asset (cash uses balance, stock uses price-on-or-before,
        // bond uses BondValuator).
        for (AssetEntity asset : b.allAssets) {
            List<EventEntity> events = sliceUpTo(b.eventsByAssetId.get(asset.id), upTo);
            if (asset.type == AssetType.CASH) {
                BigDecimal balance = sumCashNet(events);
                valueBucket.merge(asset.currency, balance, BigDecimal::add);
                continue;
            }
            FifoResult fifo = computeFifo(events);
            if (fifo.openQty.signum() == 0) continue;
            BigDecimal mv = computeMarketValueFromBulk(asset, fifo.openLots, upTo, b);
            if (mv != null) valueBucket.merge(asset.currency, mv, BigDecimal::add);
        }

        BigDecimal valueInBase = BigDecimal.ZERO;
        for (Map.Entry<Currency, BigDecimal> e : valueBucket.entrySet()) {
            BigDecimal converted = convertFromBulk(e.getValue(), e.getKey(), BASE_CURRENCY, asOf, b);
            if (converted == null) { hasFxGaps = true; continue; }
            valueInBase = valueInBase.add(converted);
        }

        BigDecimal investedInBase = BigDecimal.ZERO;
        for (AssetEntity asset : b.allAssets) {
            if (asset.type != AssetType.CASH) continue;
            BigDecimal capital = BigDecimal.ZERO;
            BigDecimal dividends = BigDecimal.ZERO;
            List<EventEntity> events = sliceUpTo(b.eventsByAssetId.get(asset.id), upTo);
            for (EventEntity ev : events) {
                if (ev.type == EventType.DIVIDEND) {
                    dividends = dividends.add(ev.amount);
                    continue;
                }
                if (ev.type == EventType.CONVERSION_IN) {
                    capital = capital.add(ev.amount);
                    BigDecimal base = convertFromBulk(
                            ev.amount, asset.currency, BASE_CURRENCY, ev.timestamp.toLocalDate(), b);
                    if (base == null) hasFxGaps = true;
                    else investedInBase = investedInBase.add(base);
                    continue;
                }
                if (ev.type == EventType.CONVERSION_OUT) {
                    capital = capital.subtract(ev.amount);
                    BigDecimal base = convertFromBulk(
                            ev.amount, asset.currency, BASE_CURRENCY, ev.timestamp.toLocalDate(), b);
                    if (base == null) hasFxGaps = true;
                    else investedInBase = investedInBase.subtract(base);
                    continue;
                }
                if (ev.incomeSourceAssetId != null) continue;
                if (b.nonCashTimestamps.contains(ev.timestamp)) continue;  // trade leg
                BigDecimal signed = (ev.type == EventType.IN) ? ev.amount : ev.amount.negate();
                capital = capital.add(signed);
                BigDecimal baseContribution = convertFromBulk(
                        signed, asset.currency, BASE_CURRENCY, ev.timestamp.toLocalDate(), b);
                if (baseContribution == null) hasFxGaps = true;
                else investedInBase = investedInBase.add(baseContribution);
            }
            investedBucket.put(asset.currency, capital);
            dividendsBucket.put(asset.currency, dividends);
        }

        for (AssetEntity asset : b.allAssets) {
            if (asset.type == AssetType.CASH) continue;
            List<EventEntity> evs = sliceUpTo(b.eventsByAssetId.get(asset.id), upTo);
            FifoResult fifo = computeFifo(evs);
            BigDecimal realized = fifo.realizedProceeds.subtract(fifo.realizedCostBasis);
            realizedBucket.merge(asset.currency, realized, BigDecimal::add);
            if (fifo.openQty.signum() > 0) {
                BigDecimal mv = computeMarketValueFromBulk(asset, fifo.openLots, upTo, b);
                if (mv != null) {
                    unrealizedBucket.merge(
                            asset.currency, mv.subtract(fifo.openCostBasis), BigDecimal::add);
                }
            }
        }

        Map<Currency, NativeBucket> bucketByCurrency = new EnumMap<>(Currency.class);
        for (Currency c : Currency.values()) {
            BigDecimal v = valueBucket.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal i = investedBucket.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal d = dividendsBucket.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal r = realizedBucket.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal u = unrealizedBucket.getOrDefault(c, BigDecimal.ZERO);
            bucketByCurrency.put(c, new NativeBucket(v, i, v.subtract(i), d, r, u));
        }

        Map<Currency, BigDecimal> valueByDisplayCurrency = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> investedByDisplayCurrency = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> pnlByDisplayCurrency = new EnumMap<>(Currency.class);
        valueByDisplayCurrency.put(BASE_CURRENCY, valueInBase);
        investedByDisplayCurrency.put(BASE_CURRENCY, investedInBase);
        pnlByDisplayCurrency.put(BASE_CURRENCY, valueInBase.subtract(investedInBase));
        for (Currency c : Currency.values()) {
            if (c == BASE_CURRENCY) continue;
            BigDecimal v = convertFromBulk(valueInBase, BASE_CURRENCY, c, asOf, b);
            BigDecimal i = convertFromBulk(investedInBase, BASE_CURRENCY, c, asOf, b);
            if (v == null || i == null) { hasFxGaps = true; continue; }
            valueByDisplayCurrency.put(c, v);
            investedByDisplayCurrency.put(c, i);
            pnlByDisplayCurrency.put(c, v.subtract(i));
        }

        return new PortfolioTotals(
                BASE_CURRENCY,
                valueInBase,
                investedInBase,
                valueInBase.subtract(investedInBase),
                valueByDisplayCurrency,
                investedByDisplayCurrency,
                pnlByDisplayCurrency,
                bucketByCurrency,
                hasFxGaps);
    }

    /** Returns the prefix of {@code chrono} (sorted by timestamp) with timestamps {@code <= upTo}. */
    private static List<EventEntity> sliceUpTo(@Nullable List<EventEntity> chrono, LocalDateTime upTo) {
        if (chrono == null || chrono.isEmpty()) return Collections.emptyList();
        // Linear scan — events per asset are typically small (<200), simpler than binarySearch.
        int hi = chrono.size();
        for (int i = 0; i < chrono.size(); i++) {
            if (chrono.get(i).timestamp.isAfter(upTo)) { hi = i; break; }
        }
        return chrono.subList(0, hi);
    }

    @Nullable
    private BigDecimal computeMarketValueFromBulk(
            AssetEntity asset, List<OpenLot> openLots, LocalDateTime upTo, BulkSnapshotData b) {
        if (asset.type == AssetType.STOCK) {
            NavigableMap<LocalDate, BigDecimal> series = b.pricesByTicker.get(asset.ticker);
            if (series == null) return null;
            Map.Entry<LocalDate, BigDecimal> entry = series.floorEntry(upTo.toLocalDate());
            if (entry == null) return null;
            BigDecimal totalQty = BigDecimal.ZERO;
            for (OpenLot lot : openLots) totalQty = totalQty.add(lot.qty);
            return totalQty.multiply(entry.getValue());
        }
        if (asset.type == AssetType.BOND) {
            return BondValuator.valueOf(asset, openLots, Collections.emptyList(), upTo);
        }
        return null;
    }

    @Nullable
    private BigDecimal convertFromBulk(
            BigDecimal amount, Currency src, Currency tgt, LocalDate on, BulkSnapshotData b) {
        if (src == tgt) return amount;
        Map<Currency, NavigableMap<LocalDate, BigDecimal>> bySrc = b.fxBySrcTgt.get(src);
        if (bySrc == null) return null;
        NavigableMap<LocalDate, BigDecimal> series = bySrc.get(tgt);
        if (series == null) return null;
        Map.Entry<LocalDate, BigDecimal> e = series.floorEntry(on);
        if (e == null) return null;
        return amount.multiply(e.getValue(), MC);
    }

    private static PortfolioValueSnapshotEntity buildSnapshotEntity(LocalDate d, PortfolioTotals t) {
        PortfolioValueSnapshotEntity s = new PortfolioValueSnapshotEntity();
        s.date = d;
        s.baseCurrency = t.baseCurrency;
        s.valueInBase = t.valueInBase;
        s.investedInBase = t.investedInBase;
        s.hasFxGaps = t.hasFxGaps;
        NativeBucket usd = t.bucketByCurrency.get(Currency.USD);
        NativeBucket eur = t.bucketByCurrency.get(Currency.EUR);
        NativeBucket uah = t.bucketByCurrency.get(Currency.UAH);
        s.valueUsd = usd != null ? usd.value : BigDecimal.ZERO;
        s.valueEur = eur != null ? eur.value : BigDecimal.ZERO;
        s.valueUah = uah != null ? uah.value : BigDecimal.ZERO;
        s.investedUsd = usd != null ? usd.invested : BigDecimal.ZERO;
        s.investedEur = eur != null ? eur.invested : BigDecimal.ZERO;
        s.investedUah = uah != null ? uah.invested : BigDecimal.ZERO;
        return s;
    }

    /**
     * Convert {@code amount} from {@code src} to {@code tgt} using the most-recent FX
     * rate on or before {@code on}. Null if no rate is available — caller marks that
     * as an FX gap.
     */
    @Nullable
    private BigDecimal convert(
            @NonNull BigDecimal amount,
            @NonNull Currency src,
            @NonNull Currency tgt,
            @NonNull LocalDate on) {
        if (src == tgt) return amount;
        ExchangeRateEntity rate = exchangeRateDao.findOnOrBefore(src, tgt, on);
        if (rate == null) return null;
        return amount.multiply(rate.rate, MC);
    }

    /**
     * Synchronous variant callers inside the repository can reuse without going through
     * executor.submit + Future.get (which would deadlock on the single-thread executor).
     */
    private List<Holding> computeHoldingsSync(LocalDate asOf) {
        LocalDateTime upTo = endOfDay(asOf);
        List<AssetEntity> all = assetDao.getAll();
        List<Holding> holdings = new ArrayList<>();
        for (AssetEntity asset : all) {
            List<EventEntity> events = eventDao.getByAssetAsOf(asset.id, upTo);
            if (asset.type == AssetType.CASH) {
                BigDecimal balance = sumCashNet(events);
                // Cash is always worth its face — market value = quantity.
                holdings.add(new Holding(asset, balance, null, balance, null, null, null));
            } else {
                FifoResult fifo = computeFifo(events);
                if (fifo.openQty.signum() == 0) continue;
                BigDecimal marketValue = computeMarketValue(asset, fifo.openLots, upTo);
                BigDecimal realized = fifo.realizedProceeds.subtract(fifo.realizedCostBasis);
                BigDecimal dividends = BigDecimal.ZERO;
                for (EventEntity inc : eventDao.getIncomeFromAssetAsOf(asset.id, upTo)) {
                    dividends = dividends.add(inc.amount);
                }
                LocalDateTime latestPurchaseAt = null;
                for (OpenLot lot : fifo.openLots) {
                    if (latestPurchaseAt == null || lot.acquiredAt.isAfter(latestPurchaseAt)) {
                        latestPurchaseAt = lot.acquiredAt;
                    }
                }
                holdings.add(new Holding(
                        asset, fifo.openQty, fifo.openCostBasis, marketValue,
                        dividends, realized, latestPurchaseAt));
            }
        }
        return holdings;
    }

    /**
     * Current market value for a STOCK or BOND in the asset's native currency.
     * STOCK: qty × latest stored close — null if {@code stock_price} has nothing for
     * this ticker yet (fresh install before sync or a manual-price asset).
     * BOND: delegated to {@link BondValuator}.
     */
    @Nullable
    private BigDecimal computeMarketValue(
            @NonNull AssetEntity asset,
            @NonNull List<OpenLot> openLots,
            @NonNull LocalDateTime upTo) {
        if (asset.type == AssetType.STOCK) {
            // Use the close on-or-before the as-of date so historical snapshots see
            // historical prices (not today's price back-applied to every past day).
            StockPriceEntity quote = stockPriceDao.findOnOrBefore(asset.ticker, upTo.toLocalDate());
            if (quote == null) return null;
            BigDecimal totalQty = BigDecimal.ZERO;
            for (OpenLot lot : openLots) totalQty = totalQty.add(lot.qty);
            return totalQty.multiply(quote.closePrice);
        }
        if (asset.type == AssetType.BOND) {
            // Bonds carry no live market price; held at face × qty for the lifetime.
            // Premium/discount surfaces as -unrealized (constant) and converts to
            // realized at sale or maturity.
            return BondValuator.valueOf(asset, openLots, Collections.emptyList(), upTo);
        }
        return null;
    }

    /**
     * FIFO lot walk over all events for {@code assetId} up to end-of-day {@code asOf}.
     * Throws for CASH assets (FIFO is not a meaningful concept there).
     */
    public Future<FifoResult> computeFifoCostBasis(long assetId, @NonNull LocalDate asOf) {
        return executor.submit(() -> {
            AssetEntity asset = assetDao.findById(assetId);
            if (asset == null) {
                throw new IllegalArgumentException("No asset with id " + assetId);
            }
            if (asset.type == AssetType.CASH) {
                throw new IllegalArgumentException("FIFO is not defined for cash assets");
            }
            return computeFifo(eventDao.getByAssetAsOf(assetId, endOfDay(asOf)));
        });
    }

    // ─── Internals ─────────────────────────────────────────────────────────

    private void recordInvestmentIncome(
            long sourceAssetId,
            AssetType expectedType,
            BigDecimal cashAmount,
            Currency currency,
            LocalDateTime timestamp) {
        AssetEntity source = assetDao.findById(sourceAssetId);
        if (source == null) {
            throw new IllegalArgumentException("No asset with id " + sourceAssetId);
        }
        if (source.type != expectedType) {
            throw new IllegalArgumentException(
                    "Asset " + sourceAssetId + " is " + source.type + ", expected " + expectedType);
        }
        writeCashEvent(currency, EventType.DIVIDEND, cashAmount, timestamp, sourceAssetId);
    }

    private void writeCashEvent(
            Currency currency,
            EventType type,
            BigDecimal amount,
            LocalDateTime timestamp,
            @Nullable Long incomeSourceAssetId) {
        AssetEntity cashAsset = requireCashAsset(currency);
        EventEntity e = new EventEntity();
        e.timestamp = timestamp;
        e.type = type;
        e.assetId = cashAsset.id;
        e.amount = amount;
        e.price = BigDecimal.ONE;
        e.incomeSourceAssetId = incomeSourceAssetId;
        eventDao.insert(e);
    }

    private AssetEntity requireCashAsset(Currency currency) {
        AssetEntity cashAsset = assetDao.findByTickerAndCurrency("CASH_" + currency.name(), currency);
        if (cashAsset == null) {
            throw new IllegalStateException(
                    "Cash pile for " + currency + " is missing — DB seed did not run");
        }
        return cashAsset;
    }

    private static BigDecimal sumCashNet(List<EventEntity> events) {
        BigDecimal net = BigDecimal.ZERO;
        for (EventEntity e : events) {
            // Inflows: external deposits + investment income + bond redemption +
            // incoming side of a currency conversion.
            if (e.type == EventType.IN
                    || e.type == EventType.DIVIDEND
                    || e.type == EventType.MATURITY
                    || e.type == EventType.CONVERSION_IN) {
                net = net.add(e.amount);
            } else if (e.type == EventType.OUT
                    || e.type == EventType.CONVERSION_OUT) {
                // Outflows: withdrawals, trade-leg cash OUT, conversion-out leg.
                net = net.subtract(e.amount);
            }
            // SPLIT events never appear on cash assets — defensive ignore.
        }
        return net;
    }

    private static LocalDateTime endOfDay(LocalDate d) {
        return d.atTime(23, 59, 59, 999_999_999);
    }

    /**
     * Pure FIFO walk over a chronologically-ordered event stream for a single asset.
     * IN events add lots; OUT events consume them oldest-first. Returns open-lot totals
     * and realized totals. An over-sell (OUT qty exceeding open lots) silently drains
     * the queue — surfacing that as an error is future work.
     */
    static FifoResult computeFifo(List<EventEntity> chronological) {
        Deque<MutableLot> openMutable = new ArrayDeque<>();
        BigDecimal realizedCost = BigDecimal.ZERO;
        BigDecimal realizedProceeds = BigDecimal.ZERO;

        for (EventEntity e : chronological) {
            if (e.type == EventType.IN) {
                openMutable.addLast(new MutableLot(e.amount, e.price, e.timestamp));
            } else if (e.type == EventType.OUT) {
                BigDecimal remaining = e.amount;
                realizedProceeds = realizedProceeds.add(e.amount.multiply(e.price));
                while (remaining.signum() > 0 && !openMutable.isEmpty()) {
                    MutableLot lot = openMutable.peekFirst();
                    BigDecimal consume = lot.qty.min(remaining);
                    realizedCost = realizedCost.add(consume.multiply(lot.price));
                    lot.qty = lot.qty.subtract(consume);
                    remaining = remaining.subtract(consume);
                    if (lot.qty.signum() == 0) openMutable.removeFirst();
                }
            } else if (e.type == EventType.SPLIT) {
                // Forward-only: scale all currently-open lots by the split ratio
                // (e.amount = numerator/denominator). qty grows by ratio, per-unit price
                // shrinks by the same ratio so cost basis stays fixed.
                BigDecimal ratio = e.amount;
                if (ratio.signum() <= 0) continue;
                Deque<MutableLot> rebuilt = new ArrayDeque<>(openMutable.size());
                for (MutableLot lot : openMutable) {
                    BigDecimal newQty = lot.qty.multiply(ratio);
                    BigDecimal newPrice = lot.price.divide(ratio, MC);
                    rebuilt.addLast(new MutableLot(newQty, newPrice, lot.acquiredAt));
                }
                openMutable.clear();
                openMutable.addAll(rebuilt);
            }
            // DIVIDEND lives on the cash asset — never reaches FIFO walkers for stocks/bonds.
        }

        BigDecimal openQty = BigDecimal.ZERO;
        BigDecimal openCost = BigDecimal.ZERO;
        List<OpenLot> openLots = new ArrayList<>(openMutable.size());
        for (MutableLot lot : openMutable) {
            openQty = openQty.add(lot.qty);
            openCost = openCost.add(lot.qty.multiply(lot.price));
            openLots.add(new OpenLot(lot.qty, lot.price, lot.acquiredAt));
        }
        return new FifoResult(openQty, openCost, realizedCost, realizedProceeds, openLots);
    }

    private static final class MutableLot {
        BigDecimal qty;
        final BigDecimal price;
        final LocalDateTime acquiredAt;
        MutableLot(BigDecimal qty, BigDecimal price, LocalDateTime acquiredAt) {
            this.qty = qty;
            this.price = price;
            this.acquiredAt = acquiredAt;
        }
    }

    /**
     * Walks a chronologically-ordered event stream and records, per IN event, the FIFO
     * consumption timeline (each OUT event's contribution — qty + sell price + timestamp).
     * The {@link TradeRow} computation needs per-sell detail to split realized P&amp;L by
     * window, which {@link #computeFifo} throws away.
     */
    static List<LotTimeline> buildLotTimelines(List<EventEntity> chronological) {
        List<LotTimeline> all = new ArrayList<>();
        Deque<MutableLotWithTimeline> queue = new ArrayDeque<>();

        for (EventEntity e : chronological) {
            if (e.type == EventType.IN) {
                MutableLotWithTimeline lot = new MutableLotWithTimeline(e);
                queue.addLast(lot);
                all.add(new LotTimeline(e, lot.consumptions));
            } else if (e.type == EventType.OUT) {
                BigDecimal remaining = e.amount;
                while (remaining.signum() > 0 && !queue.isEmpty()) {
                    MutableLotWithTimeline head = queue.peekFirst();
                    BigDecimal consume = head.remainingQty.min(remaining);
                    head.consumptions.add(new Consumption(consume, e.price, e.timestamp));
                    head.remainingQty = head.remainingQty.subtract(consume);
                    remaining = remaining.subtract(consume);
                    if (head.remainingQty.signum() == 0) queue.removeFirst();
                }
                // Over-sells beyond open lots are silently dropped, matching computeFifo.
            } else if (e.type == EventType.SPLIT) {
                // Apply the split ratio to every still-open lot's remaining qty. The
                // displayed "original qty" on TradeRow stays as the IN event's value —
                // mildly misleading after a split, accepted per the forward-only call.
                BigDecimal ratio = e.amount;
                if (ratio.signum() <= 0) continue;
                for (MutableLotWithTimeline q : queue) {
                    q.remainingQty = q.remainingQty.multiply(ratio);
                }
            }
        }
        return all;
    }

    private static final class MutableLotWithTimeline {
        BigDecimal remainingQty;
        final List<Consumption> consumptions = new ArrayList<>();
        MutableLotWithTimeline(EventEntity inEvent) {
            this.remainingQty = inEvent.amount;
        }
    }

    static final class LotTimeline {
        final EventEntity inEvent;
        final List<Consumption> consumptions;
        LotTimeline(EventEntity inEvent, List<Consumption> consumptions) {
            this.inEvent = inEvent;
            this.consumptions = consumptions;
        }
    }

    static final class Consumption {
        final BigDecimal qty;
        final BigDecimal sellPrice;
        final LocalDateTime consumedAt;
        Consumption(BigDecimal qty, BigDecimal sellPrice, LocalDateTime consumedAt) {
            this.qty = qty;
            this.sellPrice = sellPrice;
            this.consumedAt = consumedAt;
        }
    }

    // ─── DTOs ──────────────────────────────────────────────────────────────

    public static final class Holding {
        @NonNull public final AssetEntity asset;
        @NonNull public final BigDecimal quantity;
        /** FIFO open-lot cost basis for STOCK/BOND; null for CASH. Native currency. */
        @Nullable public final BigDecimal openCostBasis;
        /**
         * Current market value in the asset's native currency.
         * CASH: = quantity (cash is always worth its face).
         * STOCK: qty × latest stored close, null if no price is in the DB yet.
         * BOND: {@link com.my.finmon.domain.BondValuator} result.
         */
        @Nullable public final BigDecimal marketValue;
        /**
         * Lifetime dividends + bond coupons received from this asset (cash IN events
         * with {@code incomeSourceAssetId = asset.id}). Null for CASH. Native currency.
         */
        @Nullable public final BigDecimal lifetimeDividends;
        /**
         * Lifetime realized P&amp;L from FIFO sells/maturities on this asset
         * ({@code Σ realizedProceeds − realizedCostBasis}). Null for CASH; zero when
         * no sells have happened yet (most active holdings).
         */
        @Nullable public final BigDecimal lifetimeRealizedPnl;
        /**
         * Most recent acquisition timestamp across the asset's open lots — used by
         * the holdings list to sort newest-purchased first. Null for CASH (no lots).
         */
        @Nullable public final LocalDateTime latestPurchaseAt;

        public Holding(
                @NonNull AssetEntity asset,
                @NonNull BigDecimal quantity,
                @Nullable BigDecimal openCostBasis,
                @Nullable BigDecimal marketValue,
                @Nullable BigDecimal lifetimeDividends,
                @Nullable BigDecimal lifetimeRealizedPnl,
                @Nullable LocalDateTime latestPurchaseAt) {
            this.asset = asset;
            this.quantity = quantity;
            this.openCostBasis = openCostBasis;
            this.marketValue = marketValue;
            this.lifetimeDividends = lifetimeDividends;
            this.lifetimeRealizedPnl = lifetimeRealizedPnl;
            this.latestPurchaseAt = latestPurchaseAt;
        }
    }

    /**
     * A {@link Holding} paired with its windowed P&amp;L (realized + unrealized +
     * dividends over a {@code [windowStart, windowEnd]} range, summed across all the
     * asset's lots). Drives the Portfolio screen when the global filter is active —
     * the existing lifetime fields on {@link Holding} stay accessible for callers
     * that don't need windowing.
     */
    public static final class WindowedHolding {
        @NonNull public final Holding holding;
        @NonNull public final BigDecimal windowDividends;
        @NonNull public final BigDecimal windowRealizedPnl;
        @NonNull public final BigDecimal windowUnrealizedPnl;
        /** {@code dividends + realized + unrealized} — agrees with summed TradeRow. */
        @NonNull public final BigDecimal windowTotalPnl;

        public WindowedHolding(
                @NonNull Holding holding,
                @NonNull BigDecimal windowDividends,
                @NonNull BigDecimal windowRealizedPnl,
                @NonNull BigDecimal windowUnrealizedPnl,
                @NonNull BigDecimal windowTotalPnl) {
            this.holding = holding;
            this.windowDividends = windowDividends;
            this.windowRealizedPnl = windowRealizedPnl;
            this.windowUnrealizedPnl = windowUnrealizedPnl;
            this.windowTotalPnl = windowTotalPnl;
        }
    }

    /**
     * Expected future inflow from one of an active bond's NBU schedule rows. Used by
     * the Bonds screen for the Expected Payments card and the Calendar markers.
     * {@code amount} is in the bond's native currency, post-tax for coupons.
     */
    public static final class ExpectedPayment {
        public final long bondAssetId;
        @NonNull public final String ticker;
        @NonNull public final Currency currency;
        @NonNull public final LocalDate date;
        @NonNull public final BigDecimal amount;
        /** Either {@link EventType#DIVIDEND} (coupon) or {@link EventType#MATURITY}. */
        @NonNull public final EventType type;
        /** True for past payments already booked to the event log; false for future
         *  projections from the NBU schedule. */
        public final boolean paid;

        public ExpectedPayment(
                long bondAssetId,
                @NonNull String ticker,
                @NonNull Currency currency,
                @NonNull LocalDate date,
                @NonNull BigDecimal amount,
                @NonNull EventType type,
                boolean paid) {
            this.bondAssetId = bondAssetId;
            this.ticker = ticker;
            this.currency = currency;
            this.date = date;
            this.amount = amount;
            this.type = type;
            this.paid = paid;
        }
    }

    /**
     * One row in {@link BondTimeline}. Past entries ({@code paid=true}) come from
     * recorded events; future entries ({@code paid=false}) come from the NBU schedule
     * scaled by the bond's current open quantity.
     */
    public static final class BondTimelineEntry {
        @NonNull public final LocalDate date;
        @NonNull public final BigDecimal amount;
        @NonNull public final EventType type;
        public final boolean paid;

        public BondTimelineEntry(
                @NonNull LocalDate date,
                @NonNull BigDecimal amount,
                @NonNull EventType type,
                boolean paid) {
            this.date = date;
            this.amount = amount;
            this.type = type;
            this.paid = paid;
        }
    }

    /**
     * Past + future coupon/maturity timeline for a single bond. Drives the bond
     * detail dialog opened by tapping a bond row.
     */
    public static final class BondTimeline {
        @androidx.annotation.Nullable public final AssetEntity bond;
        @NonNull public final List<BondTimelineEntry> entries;

        public BondTimeline(
                @androidx.annotation.Nullable AssetEntity bond,
                @NonNull List<BondTimelineEntry> entries) {
            this.bond = bond;
            this.entries = entries;
        }
    }

    /**
     * Wrapper around a list of {@link ExpectedPayment} plus pre-aggregated totals so
     * the UI doesn't need to walk the rows twice.
     */
    public static final class ExpectedPaymentsResult {
        @NonNull public final List<ExpectedPayment> payments;
        /** Native-currency sums (e.g. UAH → 50,000 means 50,000 UAH expected). */
        @NonNull public final Map<Currency, BigDecimal> totalsByCurrency;
        /** Same total expressed in each display currency — picks one for the
         *  card headline, others form the "≈ …" ribbon. */
        @NonNull public final Map<Currency, BigDecimal> totalsByDisplayCurrency;
        @NonNull public final BigDecimal totalInBase;
        @NonNull public final Currency baseCurrency;
        public final boolean hasFxGaps;

        public ExpectedPaymentsResult(
                @NonNull List<ExpectedPayment> payments,
                @NonNull Map<Currency, BigDecimal> totalsByCurrency,
                @NonNull Map<Currency, BigDecimal> totalsByDisplayCurrency,
                @NonNull BigDecimal totalInBase,
                @NonNull Currency baseCurrency,
                boolean hasFxGaps) {
            this.payments = payments;
            this.totalsByCurrency = totalsByCurrency;
            this.totalsByDisplayCurrency = totalsByDisplayCurrency;
            this.totalInBase = totalInBase;
            this.baseCurrency = baseCurrency;
            this.hasFxGaps = hasFxGaps;
        }
    }

    /**
     * One pie-chart slice — a label and its value in the breakdown's display currency.
     * Slices with non-positive values are filtered out before the breakdown is returned.
     */
    public static final class Slice {
        @NonNull public final String label;
        @NonNull public final BigDecimal value;

        public Slice(@NonNull String label, @NonNull BigDecimal value) {
            this.label = label;
            this.value = value;
        }
    }

    /**
     * Internal helper for {@link #getAnalyticsAsOf} — the by-asset list needs more than
     * a label/value pair while building, but is flattened to {@link Slice} on return.
     */
    private static final class AssetSlice {
        final long assetId;
        @NonNull final String ticker;
        @NonNull final AssetType type;
        @NonNull final Currency currency;
        @NonNull final BigDecimal value;

        AssetSlice(long assetId, @NonNull String ticker, @NonNull AssetType type,
                   @NonNull Currency currency, @NonNull BigDecimal value) {
            this.assetId = assetId;
            this.ticker = ticker;
            this.type = type;
            this.currency = currency;
            this.value = value;
        }
    }

    /**
     * Three-axis breakdown returned by {@link #getAnalyticsAsOf}. All slice values are
     * in {@link #displayCurrency}. Slice lists are sorted largest-first.
     */
    public static final class AnalyticsBreakdown {
        @NonNull public final Currency displayCurrency;
        @NonNull public final List<Slice> byType;
        @NonNull public final List<Slice> byCurrency;
        @NonNull public final List<Slice> byAsset;
        public final boolean hasFxGaps;

        public AnalyticsBreakdown(
                @NonNull Currency displayCurrency,
                @NonNull List<Slice> byType,
                @NonNull List<Slice> byCurrency,
                @NonNull List<Slice> byAsset,
                boolean hasFxGaps) {
            this.displayCurrency = displayCurrency;
            this.byType = byType;
            this.byCurrency = byCurrency;
            this.byAsset = byAsset;
            this.hasFxGaps = hasFxGaps;
        }
    }

    /**
     * One matured bond — fully redeemed, has a {@code MATURITY} event. Aggregated lifetime
     * cash flows in the bond's native currency. Drives the matured-bonds section under
     * the active-holdings list. Identity: {@code realizedPnl == couponsReceived +
     * principalReturned − invested}.
     */
    public static final class MaturedBond {
        public final long assetId;
        @NonNull public final String ticker;
        @Nullable public final String name;
        @NonNull public final Currency currency;
        @Nullable public final LocalDate maturityDate;
        @NonNull public final BigDecimal invested;
        @NonNull public final BigDecimal couponsReceived;
        @NonNull public final BigDecimal principalReturned;
        @NonNull public final BigDecimal realizedPnl;

        public MaturedBond(
                long assetId,
                @NonNull String ticker,
                @Nullable String name,
                @NonNull Currency currency,
                @Nullable LocalDate maturityDate,
                @NonNull BigDecimal invested,
                @NonNull BigDecimal couponsReceived,
                @NonNull BigDecimal principalReturned,
                @NonNull BigDecimal realizedPnl) {
            this.assetId = assetId;
            this.ticker = ticker;
            this.name = name;
            this.currency = currency;
            this.maturityDate = maturityDate;
            this.invested = invested;
            this.couponsReceived = couponsReceived;
            this.principalReturned = principalReturned;
            this.realizedPnl = realizedPnl;
        }
    }

    public static final class FifoResult {
        @NonNull public final BigDecimal openQty;
        @NonNull public final BigDecimal openCostBasis;
        @NonNull public final BigDecimal realizedCostBasis;
        @NonNull public final BigDecimal realizedProceeds;
        /** Open lots remaining after FIFO consumption, in acquisition order. */
        @NonNull public final List<OpenLot> openLots;

        public FifoResult(
                @NonNull BigDecimal openQty,
                @NonNull BigDecimal openCostBasis,
                @NonNull BigDecimal realizedCostBasis,
                @NonNull BigDecimal realizedProceeds,
                @NonNull List<OpenLot> openLots) {
            this.openQty = openQty;
            this.openCostBasis = openCostBasis;
            this.realizedCostBasis = realizedCostBasis;
            this.realizedProceeds = realizedProceeds;
            this.openLots = openLots;
        }
    }

    /**
     * Per-lot P&amp;L row for the currency-breakdown screen (step 9). Evaluated over a
     * window — see {@link #getTradeRows} for the realized/unrealized semantics.
     */
    public static final class TradeRow {
        public final long assetId;
        @NonNull public final String ticker;
        @NonNull public final AssetType assetType;
        @NonNull public final Currency currency;
        @NonNull public final LocalDateTime purchasedAt;
        @NonNull public final BigDecimal originalQty;
        @NonNull public final BigDecimal remainingQty;
        @NonNull public final BigDecimal purchasePrice;
        @NonNull public final BigDecimal windowRealizedPnl;
        @NonNull public final BigDecimal windowUnrealizedPnl;
        /**
         * Income (dividends + bond coupons + maturity inflow) attributed to this lot
         * for the window. For each income event in window, the asset's payout is split
         * pro-rata across the lots open at that moment by their qty share.
         */
        @NonNull public final BigDecimal windowDividends;
        /** {@code realized + unrealized + dividends} — the lot's lifetime-in-window P&L. */
        @NonNull public final BigDecimal windowTotalPnl;

        public TradeRow(
                long assetId,
                @NonNull String ticker,
                @NonNull AssetType assetType,
                @NonNull Currency currency,
                @NonNull LocalDateTime purchasedAt,
                @NonNull BigDecimal originalQty,
                @NonNull BigDecimal remainingQty,
                @NonNull BigDecimal purchasePrice,
                @NonNull BigDecimal windowRealizedPnl,
                @NonNull BigDecimal windowUnrealizedPnl,
                @NonNull BigDecimal windowDividends,
                @NonNull BigDecimal windowTotalPnl) {
            this.assetId = assetId;
            this.ticker = ticker;
            this.assetType = assetType;
            this.currency = currency;
            this.purchasedAt = purchasedAt;
            this.originalQty = originalQty;
            this.remainingQty = remainingQty;
            this.purchasePrice = purchasePrice;
            this.windowRealizedPnl = windowRealizedPnl;
            this.windowUnrealizedPnl = windowUnrealizedPnl;
            this.windowDividends = windowDividends;
            this.windowTotalPnl = windowTotalPnl;
        }
    }

    /**
     * Input shape for {@link #ingestStockEvents}. {@code at} carries time-of-day so the
     * idempotency dedup window is the calendar day, but the row keeps a usable timestamp
     * (Yahoo gives epoch-second precision).
     */
    public static final class DividendIngest {
        @NonNull public final LocalDateTime at;
        /** Per-share cash amount in the stock's native currency. */
        @NonNull public final BigDecimal perShareAmount;

        public DividendIngest(@NonNull LocalDateTime at, @NonNull BigDecimal perShareAmount) {
            this.at = at;
            this.perShareAmount = perShareAmount;
        }
    }

    public static final class SplitIngest {
        @NonNull public final LocalDateTime at;
        /** numerator / denominator. 4 for 4-for-1 forward; 0.25 for 1-for-4 reverse. */
        @NonNull public final BigDecimal ratio;

        public SplitIngest(@NonNull LocalDateTime at, @NonNull BigDecimal ratio) {
            this.at = at;
            this.ratio = ratio;
        }
    }

    /**
     * One stored snapshot re-expressed in a chosen display currency. Produced by
     * {@link #getSnapshotsInDisplay} for the chart's display-currency picker.
     */
    public static final class ConvertedSnapshot {
        @NonNull public final LocalDate date;
        @NonNull public final BigDecimal value;
        @NonNull public final BigDecimal invested;
        public final boolean hasFxGaps;

        public ConvertedSnapshot(
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

    /** Immutable snapshot of one open lot — what BondValuator needs per-lot. */
    public static final class OpenLot {
        @NonNull public final BigDecimal qty;
        @NonNull public final BigDecimal unitPrice;
        @NonNull public final LocalDateTime acquiredAt;

        public OpenLot(
                @NonNull BigDecimal qty,
                @NonNull BigDecimal unitPrice,
                @NonNull LocalDateTime acquiredAt) {
            this.qty = qty;
            this.unitPrice = unitPrice;
            this.acquiredAt = acquiredAt;
        }
    }

    /**
     * Portfolio-level totals. All amounts in {@code valueInBase}/{@code investedInBase}/
     * {@code pnlInBase} are in {@link #BASE_CURRENCY}. The maps let the UI render a
     * multi-currency ribbon and (eventually) a per-currency breakdown screen.
     */
    public static final class PortfolioTotals {
        @NonNull public final Currency baseCurrency;
        @NonNull public final BigDecimal valueInBase;
        @NonNull public final BigDecimal investedInBase;
        @NonNull public final BigDecimal pnlInBase;
        /** Same {@code valueInBase} re-expressed in each Currency — for the display ribbon. */
        @NonNull public final Map<Currency, BigDecimal> valueByDisplayCurrency;
        /** {@code investedInBase} re-expressed in each Currency. */
        @NonNull public final Map<Currency, BigDecimal> investedByDisplayCurrency;
        /** {@code pnlInBase} re-expressed in each Currency. */
        @NonNull public final Map<Currency, BigDecimal> pnlByDisplayCurrency;
        /** Per-native-currency view with no FX crossing — for the future breakdown screen. */
        @NonNull public final Map<Currency, NativeBucket> bucketByCurrency;
        /** True if any FX conversion couldn't find a rate; UI shows a subtle hint. */
        public final boolean hasFxGaps;

        public PortfolioTotals(
                @NonNull Currency baseCurrency,
                @NonNull BigDecimal valueInBase,
                @NonNull BigDecimal investedInBase,
                @NonNull BigDecimal pnlInBase,
                @NonNull Map<Currency, BigDecimal> valueByDisplayCurrency,
                @NonNull Map<Currency, BigDecimal> investedByDisplayCurrency,
                @NonNull Map<Currency, BigDecimal> pnlByDisplayCurrency,
                @NonNull Map<Currency, NativeBucket> bucketByCurrency,
                boolean hasFxGaps) {
            this.baseCurrency = baseCurrency;
            this.valueInBase = valueInBase;
            this.investedInBase = investedInBase;
            this.pnlInBase = pnlInBase;
            this.valueByDisplayCurrency = valueByDisplayCurrency;
            this.investedByDisplayCurrency = investedByDisplayCurrency;
            this.pnlByDisplayCurrency = pnlByDisplayCurrency;
            this.bucketByCurrency = bucketByCurrency;
            this.hasFxGaps = hasFxGaps;
        }
    }

    /**
     * Native-currency bucket: market value, invested capital, total P&amp;L, plus a
     * decomposition of P&amp;L into dividends + realized + unrealized.
     *
     * <p>Identity (no FX, single currency):  {@code pnl == dividends + realizedPnl + unrealizedPnl}.
     * Holds exactly even for bonds: their accrued yield flows through {@code unrealizedPnl}
     * (via {@code BondValuator}), and any coupons already paid flow through {@code dividends}
     * — the BondValuator subtracts paid coupons from accrual so there's no double-counting.
     */
    public static final class NativeBucket {
        @NonNull public final BigDecimal value;
        @NonNull public final BigDecimal invested;
        @NonNull public final BigDecimal pnl;
        /** Dividends + bond coupons received in this currency. */
        @NonNull public final BigDecimal dividends;
        /** Realized P&amp;L from closed lots (sell_proceeds − matched_cost). */
        @NonNull public final BigDecimal realizedPnl;
        /** Unrealized P&amp;L on open positions (current_market_value − open_cost_basis). */
        @NonNull public final BigDecimal unrealizedPnl;

        public NativeBucket(
                @NonNull BigDecimal value,
                @NonNull BigDecimal invested,
                @NonNull BigDecimal pnl,
                @NonNull BigDecimal dividends,
                @NonNull BigDecimal realizedPnl,
                @NonNull BigDecimal unrealizedPnl) {
            this.value = value;
            this.invested = invested;
            this.pnl = pnl;
            this.dividends = dividends;
            this.realizedPnl = realizedPnl;
            this.unrealizedPnl = unrealizedPnl;
        }
    }
}
