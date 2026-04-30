package com.my.finmon;

import static org.junit.Assert.assertEquals;

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
 * Pins the broker-aligned bond model: coupons received do <em>not</em> affect the
 * bond's reported value. They've already been credited to the cash pile via DIVIDEND
 * events; the bond itself stays at {@code face × Σ open_qty} regardless.
 *
 * <p>This is the inverse of the previous "accrual minus coupons" formula — under that
 * model coupons reduced the bond's marked value to avoid double-counting the cash. With
 * the simpler face-only model there's nothing to double-count, so the {@code coupons}
 * arg is ignored entirely.
 */
public final class BondCouponSubtractionTest {

    @Test
    public void couponDoesNotReduceBondValue() {
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
        coupon.assetId = -1;
        coupon.amount = new BigDecimal("500");
        coupon.price = BigDecimal.ONE;
        coupon.incomeSourceAssetId = bond.id;

        BigDecimal value = BondValuator.valueOf(
                bond, lots, Collections.singletonList(coupon), asOf);

        // 1000 × 10 = 10000 — the 500 UAH coupon doesn't enter this calculation.
        assertEquals(0, value.compareTo(new BigDecimal("10000")));
    }
}
