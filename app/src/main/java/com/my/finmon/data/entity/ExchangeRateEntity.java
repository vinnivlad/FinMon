package com.my.finmon.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;

import com.my.finmon.data.model.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity(
        tableName = "exchange_rate",
        primaryKeys = {"sourceCurrency", "targetCurrency", "date"}
)
public class ExchangeRateEntity {

    @NonNull
    public Currency sourceCurrency;

    @NonNull
    public Currency targetCurrency;

    @NonNull
    public LocalDate date;

    @NonNull
    public BigDecimal rate;

    public ExchangeRateEntity() {
        this.sourceCurrency = Currency.USD;
        this.targetCurrency = Currency.USD;
        this.date = LocalDate.now();
        this.rate = BigDecimal.ONE;
    }
}
