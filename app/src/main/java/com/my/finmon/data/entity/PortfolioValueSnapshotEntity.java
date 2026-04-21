package com.my.finmon.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity(tableName = "portfolio_value")
public class PortfolioValueSnapshotEntity {

    @PrimaryKey
    @NonNull
    public LocalDate date;

    @NonNull
    public BigDecimal totalUsd;

    @NonNull
    public BigDecimal totalEur;

    @NonNull
    public BigDecimal totalUah;

    @NonNull
    public BigDecimal partUsd;

    @NonNull
    public BigDecimal partEur;

    @NonNull
    public BigDecimal partUah;

    public PortfolioValueSnapshotEntity() {
        this.date = LocalDate.now();
        this.totalUsd = BigDecimal.ZERO;
        this.totalEur = BigDecimal.ZERO;
        this.totalUah = BigDecimal.ZERO;
        this.partUsd = BigDecimal.ZERO;
        this.partEur = BigDecimal.ZERO;
        this.partUah = BigDecimal.ZERO;
    }
}
