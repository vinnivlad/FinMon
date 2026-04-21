package com.my.finmon.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity(
        tableName = "stock_price",
        primaryKeys = {"ticker", "date"}
)
public class StockPriceEntity {

    @NonNull
    public String ticker;

    @NonNull
    public LocalDate date;

    @NonNull
    public BigDecimal closePrice;

    public StockPriceEntity() {
        this.ticker = "";
        this.date = LocalDate.now();
        this.closePrice = BigDecimal.ZERO;
    }
}
