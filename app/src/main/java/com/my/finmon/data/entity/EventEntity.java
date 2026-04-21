package com.my.finmon.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.my.finmon.data.model.EventType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity(
        tableName = "event",
        foreignKeys = {
                @ForeignKey(
                        entity = AssetEntity.class,
                        parentColumns = "id",
                        childColumns = "assetId",
                        onDelete = ForeignKey.RESTRICT
                ),
                @ForeignKey(
                        entity = AssetEntity.class,
                        parentColumns = "id",
                        childColumns = "incomeSourceAssetId",
                        onDelete = ForeignKey.RESTRICT
                )
        },
        indices = {
                @Index(value = {"assetId", "timestamp"}),
                @Index("incomeSourceAssetId")
        }
)
public class EventEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public LocalDateTime timestamp;

    @NonNull
    public EventType type;

    public long assetId;

    @NonNull
    public BigDecimal amount;

    @NonNull
    public BigDecimal price;

    /**
     * When this is a cash IN representing income from a specific asset (bond coupon,
     * stock dividend, future interest types), points to that source asset. Drives the
     * P&L distinction "cash IN with source = return on investment; cash IN with null
     * source = new capital deposited." Also used by the bond valuator to subtract
     * coupons-already-received from accrued yield. Null for trades, deposits, withdrawals.
     */
    @Nullable
    public Long incomeSourceAssetId;

    public EventEntity() {
        this.timestamp = LocalDateTime.now();
        this.type = EventType.IN;
        this.amount = BigDecimal.ZERO;
        this.price = BigDecimal.ZERO;
    }
}
