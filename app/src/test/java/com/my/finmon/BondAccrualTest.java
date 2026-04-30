package com.my.finmon;

import static org.junit.Assert.assertEquals;

import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.OpenLot;
import com.my.finmon.domain.BondValuator;

import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bonds value at {@code face × Σ open_qty}, full stop — no synthetic mid-life
 * accrual, no coupon subtraction (broker-aligned model adopted 2026-04-29). Premium
 * or discount paid above/below face surfaces as constant unrealized P&amp;L during
 * hold and converts to realized at sale or maturity.
 *
 * <p>This test pins the value to face regardless of holding time or coupons received.
 */
public final class BondAccrualTest {

    @Test
    public void valueIsFaceTimesQty_singleLot() {
        AssetEntity bond = newBond();
        LocalDateTime acquired = LocalDate.of(2026, 1, 1).atStartOfDay();
        LocalDateTime asOf = LocalDate.of(2026, 7, 1).atStartOfDay();

        List<OpenLot> lots = Collections.singletonList(
                new OpenLot(new BigDecimal("10"), new BigDecimal("950"), acquired));

        BigDecimal value = BondValuator.valueOf(bond, lots, Collections.emptyList(), asOf);

        // 1000 face × 10 qty — no accrual contribution, no coupon contribution.
        assertEquals(0, value.compareTo(new BigDecimal("10000")));
    }

    @Test
    public void valueIsFaceTimesQty_multiLot() {
        AssetEntity bond = newBond();
        LocalDateTime t1 = LocalDate.of(2024, 1, 1).atStartOfDay();
        LocalDateTime t2 = LocalDate.of(2025, 6, 1).atStartOfDay();
        LocalDateTime asOf = LocalDate.of(2026, 4, 29).atStartOfDay();

        List<OpenLot> lots = Arrays.asList(
                new OpenLot(new BigDecimal("100"), new BigDecimal("1080"), t1),
                new OpenLot(new BigDecimal("50"),  new BigDecimal("1100"), t2));

        BigDecimal value = BondValuator.valueOf(bond, lots, Collections.emptyList(), asOf);

        // 1000 × (100 + 50) — independent of acquisition dates and paid prices.
        assertEquals(0, value.compareTo(new BigDecimal("150000")));
    }

    private static AssetEntity newBond() {
        AssetEntity b = new AssetEntity();
        b.id = 42;
        b.ticker = "OVDP-X";
        b.currency = Currency.UAH;
        b.type = AssetType.BOND;
        b.bondInitialPrice = new BigDecimal("1000");
        b.bondYieldPct = new BigDecimal("12");
        b.bondMaturityDate = LocalDate.of(2030, 1, 1);
        return b;
    }
}
