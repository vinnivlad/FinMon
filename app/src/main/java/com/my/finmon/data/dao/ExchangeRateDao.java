package com.my.finmon.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.my.finmon.data.entity.ExchangeRateEntity;
import com.my.finmon.data.model.Currency;

import java.time.LocalDate;
import java.util.List;

@Dao
public interface ExchangeRateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ExchangeRateEntity rate);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<ExchangeRateEntity> rates);

    @Query("SELECT * FROM exchange_rate "
            + "WHERE sourceCurrency = :src AND targetCurrency = :tgt AND date = :date")
    @Nullable
    ExchangeRateEntity find(Currency src, Currency tgt, LocalDate date);

    @Query("SELECT * FROM exchange_rate "
            + "WHERE sourceCurrency = :src AND targetCurrency = :tgt "
            + "ORDER BY date DESC LIMIT 1")
    @Nullable
    ExchangeRateEntity findMostRecent(Currency src, Currency tgt);

    @Query("SELECT MAX(date) FROM exchange_rate "
            + "WHERE sourceCurrency = :src AND targetCurrency = :tgt")
    @Nullable
    LocalDate latestDate(Currency src, Currency tgt);

    @Query("SELECT * FROM exchange_rate "
            + "WHERE sourceCurrency = :src AND targetCurrency = :tgt "
            + "AND date >= :from AND date <= :to "
            + "ORDER BY date ASC")
    List<ExchangeRateEntity> getRange(Currency src, Currency tgt, LocalDate from, LocalDate to);
}
