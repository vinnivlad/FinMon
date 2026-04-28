package com.my.finmon;

import static org.junit.Assert.assertTrue;

import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.EventEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.model.EventType;
import com.my.finmon.data.repository.PortfolioRepository.OpenLot;
import com.my.finmon.domain.BondValuator;

import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Bond value with one coupon already received — accrued growth on face is reduced by
 * the coupon amount so cash and bond aren't double-counted (the coupon was already
 * credited to the cash pile elsewhere; subtracting it from the bond's "still owed"
 * accrual leaves the total holding correct).
 *
 * <p><b>Fixture:</b>
 * <pre>
 *   bond:   face = 1000, yield = 12%/yr, maturity 2030-01-01
 *   lot:    10 units acquired 2026-01-01
 *   asOf:   2026-07-01 (181 days)
 *   coupons received: one DIVIDEND event with amount = 500 UAH at 2026-04-01
 * </pre>
 *
 * <p><b>Expected:</b> value = accrual − coupon
 *                          = (1000 · 10 · (1 + 0.12 · 181/365)) − 500
 *                          ≈ 10595.07 − 500 = 10095.07 UAH (within rounding).
 */
public final class BondCouponSubtractionTest {

    @Test
    public void couponSubtractsFromAccruedValue() {
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

        List<OpenLot> lots = Collections.singletonList(
                new OpenLot(new BigDecimal("10"), new BigDecimal("950"), acquired));

        EventEntity coupon = new EventEntity();
        coupon.id = 1;
        coupon.timestamp = LocalDate.of(2026, 4, 1).atStartOfDay();
        coupon.type = EventType.DIVIDEND;
        coupon.assetId = -1;             // would be CASH_UAH in production; not read by valuator
        coupon.amount = new BigDecimal("500");
        coupon.price = BigDecimal.ONE;
        coupon.incomeSourceAssetId = bond.id;

        BigDecimal value = BondValuator.valueOf(
                bond, lots, Collections.singletonList(coupon), asOf);

        // Accrual − coupon. Use the no-coupon test as a reference point: ~10595.07.
        BigDecimal expected = new BigDecimal("10595.0684931506849"); // accrual to 13 dp
        BigDecimal expectedAfterCoupon = expected.subtract(new BigDecimal("500"));
        BigDecimal diff = value.subtract(expectedAfterCoupon).abs();
        assertTrue("accrual minus coupon within tolerance, diff=" + diff,
                diff.compareTo(new BigDecimal("0.0001")) <= 0);
    }
}
