package com.my.finmon.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.my.finmon.data.entity.StockPriceEntity;

import java.time.LocalDate;
import java.util.List;

@Dao
public interface StockPriceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(StockPriceEntity price);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<StockPriceEntity> prices);

    @Query("SELECT * FROM stock_price WHERE ticker = :ticker AND date = :date")
    @Nullable
    StockPriceEntity find(String ticker, LocalDate date);

    @Query("SELECT * FROM stock_price WHERE ticker = :ticker ORDER BY date DESC LIMIT 1")
    @Nullable
    StockPriceEntity findMostRecent(String ticker);

    @Query("SELECT * FROM stock_price "
            + "WHERE ticker = :ticker AND date <= :onOrBefore "
            + "ORDER BY date DESC LIMIT 1")
    @Nullable
    StockPriceEntity findOnOrBefore(String ticker, LocalDate onOrBefore);

    @Query("SELECT MAX(date) FROM stock_price WHERE ticker = :ticker")
    @Nullable
    LocalDate latestDate(String ticker);

    @Query("SELECT * FROM stock_price "
            + "WHERE ticker = :ticker AND date >= :from AND date <= :to "
            + "ORDER BY date ASC")
    List<StockPriceEntity> getRange(String ticker, LocalDate from, LocalDate to);
}
