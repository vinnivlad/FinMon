package com.my.finmon.data.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;

import java.util.List;

@Dao
public interface AssetDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(AssetEntity asset);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIfAbsent(AssetEntity asset);

    @Update
    void update(AssetEntity asset);

    @Query("SELECT * FROM asset WHERE id = :id")
    @Nullable
    AssetEntity findById(long id);

    @Query("SELECT * FROM asset WHERE ticker = :ticker AND currency = :currency LIMIT 1")
    @Nullable
    AssetEntity findByTickerAndCurrency(String ticker, Currency currency);

    @Query("SELECT * FROM asset WHERE type = :type ORDER BY ticker ASC")
    List<AssetEntity> findByType(AssetType type);

    @Query("SELECT * FROM asset ORDER BY type ASC, ticker ASC")
    List<AssetEntity> getAll();

    @Query("SELECT * FROM asset ORDER BY type ASC, ticker ASC")
    LiveData<List<AssetEntity>> observeAll();

    /** Wipes the whole table — used by the JSON import flow. */
    @Query("DELETE FROM asset")
    void deleteAll();
}
