package com.my.finmon;

import static com.my.finmon.testing.TestFixture.bd;
import static com.my.finmon.testing.TestFixture.noon;
import static com.my.finmon.testing.TestFixture.sameValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.Holding;
import com.my.finmon.testing.TestFixture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/**
 * Cheapest-possible end-to-end exercise of the {@link TestFixture} scaffolding —
 * proves the fakes wire up, the executor runs, and a simple deposit + holdings
 * read round-trips.
 *
 * <p>Fixture: a single $1,000 USD deposit at noon today. No trades, no other assets.
 * Expected: getHoldingsAsOf(today) returns exactly one CASH_USD row with quantity 1000.
 */
public final class ScaffoldingSmokeTest {

    private TestFixture fx;

    @Before
    public void setUp() {
        fx = new TestFixture();
    }

    @After
    public void tearDown() {
        fx.shutdown();
    }

    @Test
    public void depositRoundTrips() throws Exception {
        LocalDate today = LocalDate.of(2026, 4, 27);
        fx.repo.recordCashDeposit(Currency.USD, bd("1000"), noon(today)).get();

        List<Holding> holdings = fx.repo.getHoldingsAsOf(today).get();

        // Cash piles always come back, even if zero — but we expect to find CASH_USD
        // with the deposit reflected in its quantity.
        Holding usdCash = null;
        for (Holding h : holdings) {
            if (h.asset.id == fx.cashUsdId) usdCash = h;
        }
        assertTrue("CASH_USD should be present", usdCash != null);
        assertTrue("CASH_USD balance should be 1000",
                sameValue(bd("1000"), usdCash.quantity));
        assertEquals("Cash holding has no cost basis", null, usdCash.openCostBasis);
    }
}
