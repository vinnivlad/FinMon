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
     * dividends, etc.) up to the cutoff. Callers sum the {@code amount} fields in
     * Java (BigDecimal) to avoid SQLite's numeric-coercion loss of precision.
     */
    @Query("SELECT * FROM event "
            + "WHERE incomeSourceAssetId = :sourceAssetId "
            + "AND type = 'IN' "
            + "AND timestamp <= :upTo "
            + "ORDER BY timestamp ASC, id ASC")
    public abstract List<EventEntity> getIncomeFromAssetAsOf(long sourceAssetId, LocalDateTime upTo);
}
