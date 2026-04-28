package com.my.finmon.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.my.finmon.data.model.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Daily snapshot of the portfolio's headline numbers. Written by PortfolioSyncWorker
 * for every completed day (..yesterday) and read by the time-series chart.
 *
 * <p>{@code valueInBase}/{@code investedInBase} are the headline numbers, in
 * {@link #baseCurrency}. The per-currency fields ({@code value*}/{@code invested*})
 * are the same data sliced by native currency with no FX crossing — fed straight
 * from {@link com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals#bucketByCurrency}.
 * They drive the per-currency charts on the breakdown pages.
 *
 * <p>{@code hasFxGaps == true} means one or more conversions inside
 * {@code getPortfolioTotals} fell through (typically a capital-flow event whose date
 * pre-dates any stored FX row for that pair). Such snapshots are undercounts; the
 * worker re-computes and upserts them on later runs once FX backfill fills the holes.
 * Per-currency fields are unaffected by FX gaps — they're native, no conversion.
 */
@Entity(tableName = "portfolio_value")
public class PortfolioValueSnapshotEntity {

    @PrimaryKey
    @NonNull
    public LocalDate date;

    @NonNull
    public Currency baseCurrency;

    @NonNull
    public BigDecimal valueInBase;

    @NonNull
    public BigDecimal investedInBase;

    public boolean hasFxGaps;

    @NonNull public BigDecimal valueUsd;
    @NonNull public BigDecimal valueEur;
    @NonNull public BigDecimal valueUah;

    @NonNull public BigDecimal investedUsd;
    @NonNull public BigDecimal investedEur;
    @NonNull public BigDecimal investedUah;

    public PortfolioValueSnapshotEntity() {
        this.date = LocalDate.now();
        this.baseCurrency = Currency.USD;
        this.valueInBase = BigDecimal.ZERO;
        this.investedInBase = BigDecimal.ZERO;
        this.hasFxGaps = false;
        this.valueUsd = BigDecimal.ZERO;
        this.valueEur = BigDecimal.ZERO;
        this.valueUah = BigDecimal.ZERO;
        this.investedUsd = BigDecimal.ZERO;
        this.investedEur = BigDecimal.ZERO;
        this.investedUah = BigDecimal.ZERO;
    }
}
