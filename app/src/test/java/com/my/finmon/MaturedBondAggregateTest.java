package com.my.finmon;

import static com.my.finmon.testing.TestFixture.bd;
import static com.my.finmon.testing.TestFixture.nineAm;
import static com.my.finmon.testing.TestFixture.noon;
import static com.my.finmon.testing.TestFixture.sameValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.my.finmon.data.repository.PortfolioRepository.MaturedBond;
import com.my.finmon.data.repository.PortfolioRepository.Side;
import com.my.finmon.data.model.Currency;
import com.my.finmon.testing.TestFixture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/**
 * Full bond lifecycle — buy at premium, two coupons, redeem at face — and verify the
 * matured-bond DTO aggregates everything correctly.
 *
 * <p><b>Fixture (5 events + 1 redemption, UAH):</b>
 * <pre>
 *   bond:    OVDP, face = 1000, yield = 12%/yr, contractual maturity 2027-01-01
 *   day 1:   deposit 12,000 UAH                 (capital)
 *   day 2:   buy 10 OVDP @ 1010                 (paid 10100; trade leg, not capital)
 *   ~3mo:    coupon 600 UAH                     (DIVIDEND, incomeSourceAssetId=bond)
 *   ~9mo:    coupon 600 UAH                     (DIVIDEND, incomeSourceAssetId=bond)
 *   day 366: redeem on contractual maturity     (face × 10 = 10000 UAH cash IN)
 * </pre>
 *
 * <p>Even though the bond was bought at a 100 UAH premium, the two 600 UAH coupons more
 * than make up for it. Net realized P&amp;L = +1100 UAH.
 *
 * <p><b>Expected:</b>
 * <ul>
 *   <li>One matured-bond row</li>
 *   <li>{@code invested} = 10100, {@code couponsReceived} = 1200,
 *       {@code principalReturned} = 10000, {@code realizedPnl} = +1100</li>
 *   <li>Identity holds: {@code realizedPnl == coupons + principal − invested}</li>
 *   <li>{@code maturityDate} on the row is the redemption date (09:00 stamp →
 *       date is the calendar day passed to {@code recordBondMaturity})</li>
 * </ul>
 */
public final class MaturedBondAggregateTest {

    private TestFixture fx;
    private long bondId;

    @Before
    public void setUp() {
        fx = new TestFixture();
        bondId = fx.addBond(
                "OVDP", Currency.UAH,
                bd("1000"), bd("12"), LocalDate.of(2027, 1, 1));
    }

    @After
    public void tearDown() {
        fx.shutdown();
    }

    @Test
    public void fullLifecycleAggregatesCorrectly() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        LocalDate coupon1 = LocalDate.of(2026, 4, 1);
        LocalDate coupon2 = LocalDate.of(2026, 10, 1);
        LocalDate redemption = LocalDate.of(2027, 1, 1);

        fx.repo.recordCashDeposit(Currency.UAH, bd("12000"), noon(d1)).get();
        fx.repo.recordStockTrade(Side.BUY, bondId, bd("10"), bd("1010"), noon(d1.plusDays(1))).get();
        fx.repo.recordCouponPayment(bondId, bd("600"), Currency.UAH, nineAm(coupon1)).get();
        fx.repo.recordCouponPayment(bondId, bd("600"), Currency.UAH, nineAm(coupon2)).get();
        fx.repo.recordBondMaturity(bondId, redemption).get();

        List<MaturedBond> matured = fx.repo.getMaturedBonds(redemption).get();
        assertEquals("one matured row", 1, matured.size());

        MaturedBond row = matured.get(0);
        assertTrue("invested 10100", sameValue(bd("10100"), row.invested));
        assertTrue("coupons 1200", sameValue(bd("1200"), row.couponsReceived));
        assertTrue("principal 10000", sameValue(bd("10000"), row.principalReturned));
        assertTrue("realized +1100", sameValue(bd("1100"), row.realizedPnl));

        assertTrue("realizedPnl identity",
                sameValue(
                        row.realizedPnl,
                        row.couponsReceived.add(row.principalReturned).subtract(row.invested)));

        assertEquals("redemption date on row", redemption, row.maturityDate);
        assertEquals("currency UAH", Currency.UAH, row.currency);
    }
}
