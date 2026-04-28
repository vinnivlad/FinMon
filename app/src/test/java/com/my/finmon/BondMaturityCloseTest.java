package com.my.finmon;

import static com.my.finmon.testing.TestFixture.bd;
import static com.my.finmon.testing.TestFixture.nineAm;
import static com.my.finmon.testing.TestFixture.noon;
import static com.my.finmon.testing.TestFixture.sameValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.my.finmon.data.repository.PortfolioRepository.FifoResult;
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
 * Bond redemption closes lots and produces a matured-bond row with the right P&amp;L.
 *
 * <p><b>Fixture (3 events + 1 redemption, UAH):</b>
 * <pre>
 *   bond:    OVDP, face = 1000, yield = 12%/yr, contractual maturity 2026-07-01
 *   day 1:   deposit 12,000 UAH       (covers the bond purchase)
 *   day 2:   buy 10 OVDP @ 1010       (premium — paid 10100, total face = 10000)
 *   day 183: redeem on the maturity date
 * </pre>
 *
 * <p>The premium-buy means we paid more than face, so on redemption we lose 100 UAH
 * (face × qty − paid × qty = 10000 − 10100 = −100). With no coupons in this fixture,
 * that's the whole P&amp;L story.
 *
 * <p><b>Expected:</b>
 * <ul>
 *   <li>{@code recordBondMaturity} returns true</li>
 *   <li>FIFO {@code openQty} = 0 after redemption</li>
 *   <li>{@code getMaturedBonds} returns exactly one row with:
 *     {@code invested} = 10100, {@code couponsReceived} = 0,
 *     {@code principalReturned} = 10000, {@code realizedPnl} = −100</li>
 *   <li>Identity check: realizedPnl == coupons + principal − invested</li>
 *   <li>Re-running {@code recordBondMaturity} returns false (idempotent)</li>
 * </ul>
 */
public final class BondMaturityCloseTest {

    private TestFixture fx;
    private long bondId;

    @Before
    public void setUp() {
        fx = new TestFixture();
        bondId = fx.addBond(
                "OVDP", Currency.UAH,
                bd("1000"), bd("12"), LocalDate.of(2026, 7, 1));
    }

    @After
    public void tearDown() {
        fx.shutdown();
    }

    @Test
    public void redemptionClosesLotsAndProducesMaturedRow() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        LocalDate redemption = LocalDate.of(2026, 7, 1);

        fx.repo.recordCashDeposit(Currency.UAH, bd("12000"), noon(d1)).get();
        fx.repo.recordStockTrade(Side.BUY, bondId, bd("10"), bd("1010"), noon(d1.plusDays(1))).get();

        Boolean wrote = fx.repo.recordBondMaturity(bondId, redemption).get();
        assertEquals("first redemption writes", Boolean.TRUE, wrote);

        FifoResult fifo = fx.repo.computeFifoCostBasis(bondId, redemption).get();
        assertTrue("openQty 0 after redemption", sameValue(bd("0"), fifo.openQty));

        List<MaturedBond> matured = fx.repo.getMaturedBonds(redemption).get();
        assertEquals("one matured row", 1, matured.size());

        MaturedBond row = matured.get(0);
        assertEquals("matured row points at the bond", bondId, row.assetId);
        assertTrue("invested 10100", sameValue(bd("10100"), row.invested));
        assertTrue("no coupons", sameValue(bd("0"), row.couponsReceived));
        assertTrue("principal 10000", sameValue(bd("10000"), row.principalReturned));
        assertTrue("realized P&L -100", sameValue(bd("-100"), row.realizedPnl));

        // Identity (per the matured-bond DTO contract).
        assertTrue("realizedPnl identity",
                sameValue(
                        row.realizedPnl,
                        row.couponsReceived.add(row.principalReturned).subtract(row.invested)));

        // Idempotent: a second call returns false (no change).
        Boolean second = fx.repo.recordBondMaturity(bondId, redemption).get();
        assertEquals("second redemption is no-op", Boolean.FALSE, second);

        // The redemption event is stamped at 09:00 on the redemption date.
        assertNotNull("MATURITY event present in the event log",
                fx.eventDao.findMaturityForAsset(bondId));
        assertEquals("MATURITY event at 09:00",
                nineAm(redemption),
                fx.eventDao.findMaturityForAsset(bondId).timestamp);
    }
}
