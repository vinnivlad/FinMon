package com.my.finmon.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.finmon.data.dao.AssetDao;
import com.my.finmon.data.dao.EventDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.EventEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.model.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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

    private final AssetDao assetDao;
    private final EventDao eventDao;
    private final ExecutorService executor;

    public PortfolioRepository(
            @NonNull AssetDao assetDao,
            @NonNull EventDao eventDao,
            @NonNull ExecutorService executor) {
        this.assetDao = assetDao;
        this.eventDao = eventDao;
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
     * Holdings for every asset as of end-of-day {@code asOf}. Cash piles are always
     * included (even if zero); STOCK/BOND with zero remaining qty are filtered out.
     */
    public Future<List<Holding>> getHoldingsAsOf(@NonNull LocalDate asOf) {
        return executor.submit(() -> {
            LocalDateTime upTo = endOfDay(asOf);
            List<AssetEntity> all = assetDao.getAll();
            List<Holding> holdings = new ArrayList<>();
            for (AssetEntity asset : all) {
                List<EventEntity> events = eventDao.getByAssetAsOf(asset.id, upTo);
                if (asset.type == AssetType.CASH) {
                    holdings.add(new Holding(asset, sumCashNet(events), null));
                } else {
                    FifoResult fifo = computeFifo(events);
                    if (fifo.openQty.signum() == 0) continue;
                    holdings.add(new Holding(asset, fifo.openQty, fifo.openCostBasis));
                }
            }
            return holdings;
        });
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
        Deque<MutableLot> openLots = new ArrayDeque<>();
        BigDecimal realizedCost = BigDecimal.ZERO;
        BigDecimal realizedProceeds = BigDecimal.ZERO;

        for (EventEntity e : chronological) {
            if (e.type == EventType.IN) {
                openLots.addLast(new MutableLot(e.amount, e.price));
            } else {
                BigDecimal remaining = e.amount;
                realizedProceeds = realizedProceeds.add(e.amount.multiply(e.price));
                while (remaining.signum() > 0 && !openLots.isEmpty()) {
                    MutableLot lot = openLots.peekFirst();
                    BigDecimal consume = lot.qty.min(remaining);
                    realizedCost = realizedCost.add(consume.multiply(lot.price));
                    lot.qty = lot.qty.subtract(consume);
                    remaining = remaining.subtract(consume);
                    if (lot.qty.signum() == 0) openLots.removeFirst();
                }
            }
        }

        BigDecimal openQty = BigDecimal.ZERO;
        BigDecimal openCost = BigDecimal.ZERO;
        for (MutableLot lot : openLots) {
            openQty = openQty.add(lot.qty);
            openCost = openCost.add(lot.qty.multiply(lot.price));
        }
        return new FifoResult(openQty, openCost, realizedCost, realizedProceeds);
    }

    private static final class MutableLot {
        BigDecimal qty;
        final BigDecimal price;
        MutableLot(BigDecimal qty, BigDecimal price) {
            this.qty = qty;
            this.price = price;
        }
    }

    // ─── DTOs ──────────────────────────────────────────────────────────────

    public static final class Holding {
        @NonNull public final AssetEntity asset;
        @NonNull public final BigDecimal quantity;
        /** FIFO open-lot cost basis for STOCK/BOND; null for CASH. */
        @Nullable public final BigDecimal openCostBasis;

        public Holding(
                @NonNull AssetEntity asset,
                @NonNull BigDecimal quantity,
                @Nullable BigDecimal openCostBasis) {
            this.asset = asset;
            this.quantity = quantity;
            this.openCostBasis = openCostBasis;
        }
    }

    public static final class FifoResult {
        @NonNull public final BigDecimal openQty;
        @NonNull public final BigDecimal openCostBasis;
        @NonNull public final BigDecimal realizedCostBasis;
        @NonNull public final BigDecimal realizedProceeds;

        public FifoResult(
                @NonNull BigDecimal openQty,
                @NonNull BigDecimal openCostBasis,
                @NonNull BigDecimal realizedCostBasis,
                @NonNull BigDecimal realizedProceeds) {
            this.openQty = openQty;
            this.openCostBasis = openCostBasis;
            this.realizedCostBasis = realizedCostBasis;
            this.realizedProceeds = realizedProceeds;
        }
    }
}
