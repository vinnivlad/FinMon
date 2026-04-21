package com.my.finmon.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity(
        tableName = "asset",
        indices = {
                @Index(value = {"ticker", "currency"}, unique = true)
        }
)
public class AssetEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String ticker;

    @NonNull
    public Currency currency;

    @NonNull
    public AssetType type;

    @Nullable
    public LocalDate bondMaturityDate;

    @Nullable
    public BigDecimal bondInitialPrice;

    @Nullable
    public BigDecimal bondYieldPct;

    public AssetEntity() {
        this.ticker = "";
        this.currency = Currency.USD;
        this.type = AssetType.STOCK;
    }
}
