package com.my.finmon.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.finmon.data.dao.AssetDao;
import com.my.finmon.data.dao.EventDao;
import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.EventEntity;
import com.my.finmon.data.entity.ExchangeRateEntity;
import com.my.finmon.data.entity.StockPriceEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.model.EventType;
import com.my.finmon.domain.BondValuator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
    private final ExecutorService executor;

    public PortfolioRepository(
            @NonNull AssetDao assetDao,
            @NonNull EventDao eventDao,
            @NonNull StockPriceDao stockPriceDao,
            @NonNull ExchangeRateDao exchangeRateDao,
            @NonNull ExecutorService executor) {
        this.assetDao = assetDao;
        this.eventDao = eventDao;
        this.stockPriceDao = stockPriceDao;
        this.exchangeRateDao = exchangeRateDao;
        this.executor = executor;
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
     * All non-cash assets — what the "record trade" picker offers. Ordered by
     * {@code type ASC, ticker ASC} to keep stocks and bonds grouped.
     */
    @NonNull
    public Future<List<AssetEntity>> listTradeableAssets() {
        return executor.submit(() -> {
            List<AssetEntity> stocks = assetDao.findByType(AssetType.STOCK);
            List<AssetEntity> bonds = assetDao.findByType(AssetType.BOND);
            List<AssetEntity> all = new ArrayList<>(stocks.size() + bonds.size());
            all.addAll(bonds);  // BOND sorts before STOCK alphabetically
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
     * Portfolio-wide totals as of {@code asOf}:
     * <ul>
     *   <li>{@code valueInBase} / {@code investedInBase} / {@code pnlInBase} — the
     *       headline numbers, in {@link #BASE_CURRENCY}. Each holding's native market
     *       value is converted via {@code ExchangeRateEntity} on {@code asOf}; each
     *       capital-flow cash event is converted via FX on the <em>event's</em> date
     *       (so FX drift itself shows up as market P&amp;L, by design).</li>
     *   <li>{@code valueByDisplayCurrency} — the same {@code valueInBase} re-expressed
     *       in each Currency for the header ribbon.</li>
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

    private PortfolioTotals computeTotalsSync(LocalDate asOf) {
        List<Holding> holdings = computeHoldingsSync(asOf);
        LocalDateTime upTo = endOfDay(asOf);

        Map<Currency, BigDecimal> valueBucket = new EnumMap<>(Currency.class);
        Map<Currency, BigDecimal> investedBucket = new EnumMap<>(Currency.class);

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

        // Invested: sum cash events that represent EXTERNAL capital flow — standalone
        // deposits and withdrawals only. Trade legs (cash OUT on buy / cash IN on sell)
        // share a timestamp with their paired stock/bond event and are filtered out;
        // the money didn't leave the portfolio, it just changed form. Per-bucket stays
        // native. Base conversion uses FX at the event's date, so FX moves after
        // deposit are captured as market P&L.
        BigDecimal investedInBase = BigDecimal.ZERO;
        for (AssetEntity cashAsset : assetDao.findByType(AssetType.CASH)) {
            BigDecimal bucket = BigDecimal.ZERO;
            List<EventEntity> events = eventDao.getByAssetAsOf(cashAsset.id, upTo);
            for (EventEntity ev : events) {
                if (ev.incomeSourceAssetId != null) continue;  // coupons/dividends are not capital
                if (eventDao.countNonCashEventsAt(ev.timestamp) > 0) continue;  // trade leg
                BigDecimal signed = (ev.type == EventType.IN) ? ev.amount : ev.amount.negate();
                bucket = bucket.add(signed);
                BigDecimal baseContribution = convert(
                        signed, cashAsset.currency, BASE_CURRENCY, ev.timestamp.toLocalDate());
                if (baseContribution == null) {
                    hasFxGaps = true;
                } else {
                    investedInBase = investedInBase.add(baseContribution);
                }
            }
            investedBucket.put(cashAsset.currency, bucket);
        }

        // Per-currency bucket P&L (no FX crossing).
        Map<Currency, NativeBucket> bucketByCurrency = new EnumMap<>(Currency.class);
        for (Currency c : Currency.values()) {
            BigDecimal v = valueBucket.getOrDefault(c, BigDecimal.ZERO);
            BigDecimal i = investedBucket.getOrDefault(c, BigDecimal.ZERO);
            bucketByCurrency.put(c, new NativeBucket(v, i, v.subtract(i)));
        }

        // Display ribbon — same baseValue re-expressed in each Currency.
        Map<Currency, BigDecimal> valueByDisplayCurrency = new EnumMap<>(Currency.class);
        valueByDisplayCurrency.put(BASE_CURRENCY, valueInBase);
        for (Currency c : Currency.values()) {
            if (c == BASE_CURRENCY) continue;
            BigDecimal display = convert(valueInBase, BASE_CURRENCY, c, asOf);
            if (display == null) { hasFxGaps = true; continue; }
            valueByDisplayCurrency.put(c, display);
        }

        return new PortfolioTotals(
                BASE_CURRENCY,
                valueInBase,
                investedInBase,
                valueInBase.subtract(investedInBase),
                valueByDisplayCurrency,
                bucketByCurrency,
                hasFxGaps);
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
                holdings.add(new Holding(asset, balance, null, balance));
            } else {
                FifoResult fifo = computeFifo(events);
                if (fifo.openQty.signum() == 0) continue;
                BigDecimal marketValue = computeMarketValue(asset, fifo.openLots, upTo);
                holdings.add(new Holding(asset, fifo.openQty, fifo.openCostBasis, marketValue));
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
            StockPriceEntity latest = stockPriceDao.findMostRecent(asset.ticker);
            if (latest == null) return null;
            BigDecimal totalQty = BigDecimal.ZERO;
            for (OpenLot lot : openLots) totalQty = totalQty.add(lot.qty);
            return totalQty.multiply(latest.closePrice);
        }
        if (asset.type == AssetType.BOND) {
            List<EventEntity> coupons = eventDao.getIncomeFromAssetAsOf(asset.id, upTo);
            return BondValuator.valueOf(asset, openLots, coupons, upTo);
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
        writeCashEvent(currency, EventType.IN, cashAmount, timestamp, sourceAssetId);
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
            if (e.type == EventType.IN) net = net.add(e.amount);
            else net = net.subtract(e.amount);
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
            } else {
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
            }
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

        public Holding(
                @NonNull AssetEntity asset,
                @NonNull BigDecimal quantity,
                @Nullable BigDecimal openCostBasis,
                @Nullable BigDecimal marketValue) {
            this.asset = asset;
            this.quantity = quantity;
            this.openCostBasis = openCostBasis;
            this.marketValue = marketValue;
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
                @NonNull Map<Currency, NativeBucket> bucketByCurrency,
                boolean hasFxGaps) {
            this.baseCurrency = baseCurrency;
            this.valueInBase = valueInBase;
            this.investedInBase = investedInBase;
            this.pnlInBase = pnlInBase;
            this.valueByDisplayCurrency = valueByDisplayCurrency;
            this.bucketByCurrency = bucketByCurrency;
            this.hasFxGaps = hasFxGaps;
        }
    }

    /** Native-currency bucket: market value, invested (net cash deposits), and P&amp;L — no FX. */
    public static final class NativeBucket {
        @NonNull public final BigDecimal value;
        @NonNull public final BigDecimal invested;
        @NonNull public final BigDecimal pnl;

        public NativeBucket(
                @NonNull BigDecimal value,
                @NonNull BigDecimal invested,
                @NonNull BigDecimal pnl) {
            this.value = value;
            this.invested = invested;
            this.pnl = pnl;
        }
    }
}
