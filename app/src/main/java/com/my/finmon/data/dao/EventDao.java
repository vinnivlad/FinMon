package com.my.finmon.data.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import com.my.finmon.data.entity.EventEntity;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Dao
public abstract class EventDao {

    @Insert
    public abstract long insert(EventEntity event);

    @Insert
    public abstract List<Long> insertAll(List<EventEntity> events);

    /**
     * Writes both legs of a trade (stock/bond asset + cash pile) atomically.
     * Room's {@code @Transaction} wraps the inserts in a single SQLite transaction:
     * either both rows land or neither does, even if one insert throws.
     */
    @Transaction
    public List<Long> insertTradePair(EventEntity assetLeg, EventEntity cashLeg) {
        return insertAll(Arrays.asList(assetLeg, cashLeg));
    }

    @Query("SELECT * FROM event WHERE assetId = :assetId ORDER BY timestamp ASC, id ASC")
    public abstract List<EventEntity> getByAssetChronological(long assetId);

    @Query("SELECT * FROM event WHERE assetId = :assetId AND timestamp <= :upTo "
            + "ORDER BY timestamp ASC, id ASC")
    public abstract List<EventEntity> getByAssetAsOf(long assetId, LocalDateTime upTo);

    @Query("SELECT * FROM event ORDER BY timestamp DESC, id DESC LIMIT :limit")
    public abstract LiveData<List<EventEntity>> observeRecent(int limit);

    @Query("SELECT MIN(timestamp) FROM event")
    @Nullable
    public abstract LocalDateTime earliestTimestamp();

    @Query("SELECT COUNT(*) FROM event WHERE assetId = :assetId")
    public abstract int countForAsset(long assetId);

    /**
     * All income cash-ins attributable to a given source asset (bond coupons, stock
     * dividends) up to the cutoff. Callers sum the {@code amount} fields in Java
     * (BigDecimal) to avoid SQLite's numeric-coercion loss of precision.
     *
     * <p>Filters on {@code type = 'DIVIDEND'} — both stock dividends and bond coupons
     * use that type since 2026-04-26.
     */
    @Query("SELECT * FROM event "
            + "WHERE incomeSourceAssetId = :sourceAssetId "
            + "AND type = 'DIVIDEND' "
            + "AND timestamp <= :upTo "
            + "ORDER BY timestamp ASC, id ASC")
    public abstract List<EventEntity> getIncomeFromAssetAsOf(long sourceAssetId, LocalDateTime upTo);

    /**
     * Idempotency probe for auto-ingested dividends. Returns any DIVIDEND event from
     * {@code sourceAssetId} dated within {@code [startOfDay, endOfDayExclusive)}.
     * Used to skip re-creating an event the worker (or a manual entry) has already written.
     */
    @Query("SELECT * FROM event "
            + "WHERE incomeSourceAssetId = :sourceAssetId "
            + "AND type = 'DIVIDEND' "
            + "AND timestamp >= :startOfDay AND timestamp < :endOfDayExclusive "
            + "LIMIT 1")
    @Nullable
    public abstract EventEntity findDividendOnDate(
            long sourceAssetId, LocalDateTime startOfDay, LocalDateTime endOfDayExclusive);

    /**
     * Idempotency probe for auto-ingested stock splits. A given asset has at most one
     * split per day in practice; we dedup on (assetId, date).
     */
    @Query("SELECT * FROM event "
            + "WHERE assetId = :assetId "
            + "AND type = 'SPLIT' "
            + "AND timestamp >= :startOfDay AND timestamp < :endOfDayExclusive "
            + "LIMIT 1")
    @Nullable
    public abstract EventEntity findSplitOnDate(
            long assetId, LocalDateTime startOfDay, LocalDateTime endOfDayExclusive);

    /**
     * Number of events at exactly {@code ts} whose asset is NOT cash. Used by the
     * portfolio-totals query to distinguish a trade-leg cash event (paired with a
     * stock/bond event at the same timestamp) from a standalone deposit or withdrawal.
     * Only the latter counts as external capital.
     */
    @Query("SELECT COUNT(*) FROM event e "
            + "INNER JOIN asset a ON e.assetId = a.id "
            + "WHERE e.timestamp = :ts AND a.type != 'CASH'")
    public abstract int countNonCashEventsAt(LocalDateTime ts);

    @Query("SELECT * FROM event ORDER BY timestamp ASC, id ASC")
    public abstract List<EventEntity> getAllChronological();

    /** Wipes the whole table — used by the JSON import flow. */
    @Query("DELETE FROM event")
    public abstract void deleteAll();
}
