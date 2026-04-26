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
     * Symbol understood by the active remote price source. Today that's Yahoo:
     * bare for US-listed (e.g. {@code VOO}), exchange-suffixed for non-US (e.g.
     * {@code SXR8.DE}). Null for assets the remote source can't price — CASH piles,
     * Ukrainian OVDPs (UA bonds aren't on Yahoo), or any manual-price instrument.
     * The sync worker silently skips rows where this is null.
     *
     * Kept separate from {@link #ticker} so the domain symbol ("VOO") stays clean and
     * we don't couple the schema to one remote source. Renamed from {@code stooqTicker}
     * when the price source moved to Yahoo (2026-04-26).
     */
    @Nullable
    public String remoteTicker;

    /**
     * Human-readable label populated when an asset is added via the trade-form's
     * autocomplete (Yahoo's {@code longname}/{@code shortname}, or NBU's bond name).
     * Optional — assets created manually leave this null.
     */
    @Nullable
    public String name;

    /**
     * ISIN — set for UA OVDPs added via the NBU autocomplete (Y4). Drives matching
     * against NBU's {@code depo_securities} feed for coupon-schedule auto-ingest.
     * Null for assets that don't have one (stocks use {@link #remoteTicker} instead).
     */
    @Nullable
    public String isin;

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
