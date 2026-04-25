package com.my.finmon.data.dao;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.my.finmon.data.entity.PortfolioValueSnapshotEntity;

import java.time.LocalDate;
import java.util.List;

@Dao
public interface PortfolioValueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PortfolioValueSnapshotEntity snapshot);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<PortfolioValueSnapshotEntity> snapshots);

    @Query("SELECT * FROM portfolio_value WHERE date = :date")
    @Nullable
    PortfolioValueSnapshotEntity find(LocalDate date);

    @Query("SELECT * FROM portfolio_value WHERE date >= :from AND date <= :to ORDER BY date ASC")
    List<PortfolioValueSnapshotEntity> getRange(LocalDate from, LocalDate to);

    @Query("SELECT * FROM portfolio_value WHERE date >= :from AND date <= :to ORDER BY date ASC")
    LiveData<List<PortfolioValueSnapshotEntity>> observeRange(LocalDate from, LocalDate to);

    @Query("SELECT MAX(date) FROM portfolio_value")
    @Nullable
    LocalDate latestDate();

    @Query("SELECT * FROM portfolio_value WHERE hasFxGaps = 1 AND date <= :upTo ORDER BY date ASC")
    List<PortfolioValueSnapshotEntity> findGappyUpTo(LocalDate upTo);
}
