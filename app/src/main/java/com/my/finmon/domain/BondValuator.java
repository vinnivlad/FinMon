package com.my.finmon.domain;

import androidx.annotation.NonNull;

import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.EventEntity;
import com.my.finmon.data.repository.PortfolioRepository.OpenLot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Bond market value (broker-aligned model, decided 2026-04-29):
 * <pre>
 *   value = face × Σ open_qty
 * </pre>
 *
 * No mid-life synthetic accrual, no coupon subtraction. Bonds report at face value
 * during hold; premium/discount paid above-or-below face surfaces as constant
 * unrealized P&amp;L (= face × qty − cost), which converts to realized at the moment
 * of sale or maturity (FIFO {@code (face − lot_price) × qty}). Coupons go to cash
 * via DIVIDEND events as they always have.
 *
 * <p>This matches how brokerage statements report — the previous "sawtooth" formula
 * (accrual minus paid coupons) was internally consistent but didn't line up with
 * receipts. See {@code project_domain_model.md} for the full rationale.
 *
 * <p>The {@code couponsReceived} and {@code asOf} parameters are unused by the new
 * formula but kept on the signature so existing call sites (and unit-test fixtures)
 * compile unchanged.
 */
public final class BondValuator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private BondValuator() {}

    @NonNull
    public static BigDecimal valueOf(
            @NonNull AssetEntity bond,
            @NonNull List<OpenLot> openLots,
            @NonNull List<EventEntity> couponsReceived,
            @NonNull LocalDateTime asOf) {

        if (bond.bondInitialPrice == null) {
            throw new IllegalArgumentException(
                    "Bond " + bond.id + " (" + bond.ticker + ") missing face value");
        }

        BigDecimal face = bond.bondInitialPrice;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (OpenLot lot : openLots) totalQty = totalQty.add(lot.qty);
        return face.multiply(totalQty, MC);
    }
}
