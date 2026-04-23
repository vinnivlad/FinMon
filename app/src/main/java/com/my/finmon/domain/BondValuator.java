package com.my.finmon.domain;

import androidx.annotation.NonNull;

import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.EventEntity;
import com.my.finmon.data.repository.PortfolioRepository.OpenLot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Current value of a bond asset via the coupon-bond simple-interest model
 * (Option B, decided 2026-04-20):
 * <pre>
 *   value = Σ_lots (face × qty × (1 + yield · Δt_years))
 *         − Σ coupons_received
 * </pre>
 *
 * The bond's accrued value grows on the <em>face value</em> (not what the user paid),
 * per lot, from each lot's own acquisition timestamp. Coupons already received are
 * subtracted so the same money isn't counted twice (once in the cash pile, once in the
 * bond). Accrual time is capped at {@code bond.bondMaturityDate} — real bonds stop
 * accruing at maturity — but the coupon sum is <em>not</em> capped: post-maturity
 * payouts still count toward cash and therefore must still be subtracted from accrued.
 *
 * See {@code project_domain_model.md} for the full rationale.
 */
public final class BondValuator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final BigDecimal PERCENT = new BigDecimal("100");

    private BondValuator() {}

    /**
     * {@code couponsReceived} must already be filtered to {@code IN} events where
     * {@code incomeSourceAssetId = bond.id} and {@code timestamp <= asOf} (see
     * {@code EventDao.getIncomeFromAssetAsOf}).
     */
    @NonNull
    public static BigDecimal valueOf(
            @NonNull AssetEntity bond,
            @NonNull List<OpenLot> openLots,
            @NonNull List<EventEntity> couponsReceived,
            @NonNull LocalDateTime asOf) {

        if (bond.bondInitialPrice == null || bond.bondYieldPct == null) {
            throw new IllegalArgumentException(
                    "Bond " + bond.id + " (" + bond.ticker + ") missing face or yield");
        }

        BigDecimal face = bond.bondInitialPrice;
        BigDecimal yieldFrac = bond.bondYieldPct.divide(PERCENT, MC);

        LocalDateTime accrualCutoff = asOf;
        if (bond.bondMaturityDate != null) {
            LocalDateTime matAtEod = bond.bondMaturityDate.atTime(23, 59, 59);
            if (accrualCutoff.isAfter(matAtEod)) accrualCutoff = matAtEod;
        }

        BigDecimal totalAccrued = BigDecimal.ZERO;
        for (OpenLot lot : openLots) {
            long days = ChronoUnit.DAYS.between(lot.acquiredAt, accrualCutoff);
            if (days < 0) days = 0;  // acquired after cutoff — no accrual (defensive)

            BigDecimal years = new BigDecimal(days).divide(DAYS_PER_YEAR, MC);
            BigDecimal growth = BigDecimal.ONE.add(yieldFrac.multiply(years, MC));
            BigDecimal lotValue = face.multiply(lot.qty, MC).multiply(growth, MC);
            totalAccrued = totalAccrued.add(lotValue);
        }

        BigDecimal totalCoupons = BigDecimal.ZERO;
        for (EventEntity c : couponsReceived) totalCoupons = totalCoupons.add(c.amount);

        return totalAccrued.subtract(totalCoupons);
    }
}
