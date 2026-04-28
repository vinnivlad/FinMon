package com.my.finmon;

import static org.junit.Assert.assertTrue;

import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.OpenLot;
import com.my.finmon.domain.BondValuator;

import org.junit.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Bond accrual with no coupons — simple-interest growth on face value, per lot, from
 * the lot's own acquisition timestamp. Pure BondValuator test, no DAOs.
 *
 * <p><b>Fixture:</b>
 * <pre>
 *   bond:   face = 1000, yield = 12%/yr, maturity 2030-01-01
 *   lot:    10 units acquired 2026-01-01 (paid price irrelevant — accrual is on face)
 *   asOf:   2026-07-01 (exactly 181 days elapsed)
 *   coupons received: none
 * </pre>
 *
 * <p>Days are counted via {@code ChronoUnit.DAYS} between the lot's acquisition and the
 * cutoff, then divided by 365 to convert to years. 181/365 = 0.495890... so:
 *
 * <p><b>Expected:</b> value = 1000 · 10 · (1 + 0.12 · 181/365) = 10000 · (1 + 0.0595068...)
 *                          ≈ 10595.07 UAH (within a few millicents of rounding).
 */
public final class BondAccrualTest {

    @Test
    public void singleLotSixMonthsNoCoupons() {
        AssetEntity bond = new AssetEntity();
        bond.id = 42;
        bond.ticker = "OVDP-X";
        bond.currency = Currency.UAH;
        bond.type = AssetType.BOND;
        bond.bondInitialPrice = new BigDecimal("1000");
        bond.bondYieldPct = new BigDecimal("12");
        bond.bondMaturityDate = LocalDate.of(2030, 1, 1);

        LocalDateTime acquired = LocalDate.of(2026, 1, 1).atStartOfDay();
        LocalDateTime asOf = LocalDate.of(2026, 7, 1).atStartOfDay();
        // 181 days elapsed (2026 is not a leap year — Jan + Feb + Mar + Apr + May + Jun = 31+28+31+30+31+30 = 181).

        List<OpenLot> lots = Collections.singletonList(
                new OpenLot(new BigDecimal("10"), new BigDecimal("950" /* paid */), acquired));

        BigDecimal value = BondValuator.valueOf(bond, lots, Collections.emptyList(), asOf);

        // Expected via the formula, rounded for comparison: 10000 · (1 + 0.12 · 181/365)
        MathContext mc = new MathContext(12, RoundingMode.HALF_UP);
        BigDecimal years = new BigDecimal("181").divide(new BigDecimal("365"), mc);
        BigDecimal expected = new BigDecimal("10000")
                .multiply(BigDecimal.ONE.add(new BigDecimal("0.12").multiply(years, mc)), mc);

        // Compare with reasonable tolerance — both values use MathContext(12, HALF_UP).
        BigDecimal diff = value.subtract(expected).abs();
        assertTrue("accrual within rounding tolerance, was diff=" + diff,
                diff.compareTo(new BigDecimal("0.0001")) <= 0);
    }
}
