package com.my.finmon.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.my.finmon.data.entity.BondSchedulePaymentEntity;

import java.util.List;

/**
 * Persistent NBU schedule cache, one row per scheduled payment. Backs the
 * "NBU dropped the bond at maturity but I still need the final coupon"
 * recovery path.
 */
@Dao
public abstract class BondSchedulePaymentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertAll(List<BondSchedulePaymentEntity> rows);

    @Query("SELECT * FROM bond_schedule_payment WHERE assetId = :assetId "
            + "ORDER BY payDate ASC, payType ASC")
    public abstract List<BondSchedulePaymentEntity> findByAsset(long assetId);

    @Query("DELETE FROM bond_schedule_payment WHERE assetId = :assetId")
    public abstract void deleteByAsset(long assetId);

    /**
     * Atomically replaces the whole cached schedule for {@code assetId}. Used after
     * a successful NBU fetch so any stale row (rare schedule restructure) is dropped
     * along with the upsert.
     */
    @Transaction
    public void replaceForAsset(long assetId, List<BondSchedulePaymentEntity> rows) {
        deleteByAsset(assetId);
        if (!rows.isEmpty()) upsertAll(rows);
    }

    /** Wipes the whole table — used by the JSON import flow. */
    @Query("DELETE FROM bond_schedule_payment")
    public abstract void deleteAll();
}
