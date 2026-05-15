package com.my.finmon.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Persistent per-bond cache of NBU's coupon + redemption schedule. Mirrors
 * {@link com.my.finmon.data.remote.nbu.NbuBondDto.Payment} one row per
 * scheduled payment. Written whenever a {@code findBondByIsin} call returns
 * non-null payments; read back when NBU later drops the bond (matured bonds
 * disappear from the {@code depo_securities} feed entirely) so the sync can
 * still ingest a final coupon + record maturity from cached data.
 *
 * <p>FK CASCADE on {@code assetId}: when an asset is deleted (import wipe,
 * manual delete) its schedule rows go with it — no orphans.
 */
@Entity(
        tableName = "bond_schedule_payment",
        primaryKeys = {"assetId", "payDate", "payType"},
        foreignKeys = @ForeignKey(
                entity = AssetEntity.class,
                parentColumns = "id",
                childColumns = "assetId",
                onDelete = ForeignKey.CASCADE),
        indices = @Index("assetId")
)
public class BondSchedulePaymentEntity {

    public long assetId;

    @NonNull
    public LocalDate payDate;

    /** {@code "1"} = coupon, {@code "2"} = principal repayment at maturity. */
    @NonNull
    public String payType;

    /** Per-bond-unit payment amount in the bond's native currency. */
    @NonNull
    public BigDecimal payVal;

    public BondSchedulePaymentEntity() {
        this.payDate = LocalDate.now();
        this.payType = "1";
        this.payVal = BigDecimal.ZERO;
    }
}
