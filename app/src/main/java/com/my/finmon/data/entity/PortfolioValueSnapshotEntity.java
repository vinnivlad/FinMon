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
 * Values are in {@code baseCurrency} — persisted so snapshots survive a future
 * base-currency switch without silently re-interpreting historical rows.
 *
 * {@code hasFxGaps == true} means one or more conversions inside
 * {@code getPortfolioTotals} fell through (typically a capital-flow event whose date
 * pre-dates any stored FX row for that pair). Such snapshots are undercounts; the
 * worker re-computes and upserts them on later runs once FX backfill fills the holes.
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

    public PortfolioValueSnapshotEntity() {
        this.date = LocalDate.now();
        this.baseCurrency = Currency.USD;
        this.valueInBase = BigDecimal.ZERO;
        this.investedInBase = BigDecimal.ZERO;
        this.hasFxGaps = false;
    }
}
