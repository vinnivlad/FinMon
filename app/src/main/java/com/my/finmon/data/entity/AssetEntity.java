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

    /**
     * Exchange-suffixed ticker understood by Stooq (e.g. {@code aapl.us}, {@code vwce.de}).
     * Null for assets Stooq can't price — CASH piles, Ukrainian OVDPs (no {@code .ua}
     * coverage), or any manual-price instrument. The sync worker silently skips rows
     * where this is null.
     *
     * Kept separate from {@link #ticker} so the domain symbol ("AAPL") stays clean and we
     * don't couple the schema to one remote source. If we later swap Stooq, only this
     * column (and the Stooq fetch path) changes.
     */
    @Nullable
    public String stooqTicker;

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
