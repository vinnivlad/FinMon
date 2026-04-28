package com.my.finmon;

import static com.my.finmon.testing.TestFixture.bd;
import static com.my.finmon.testing.TestFixture.noon;
import static com.my.finmon.testing.TestFixture.sameValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.NativeBucket;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.data.repository.PortfolioRepository.Side;
import com.my.finmon.testing.TestFixture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;

/**
 * Trade-leg cash events should NOT count as external capital. The detection rule:
 * a cash event at a timestamp where any non-cash event also exists is treated as the
 * cash leg of a trade pair and skipped from {@code invested}. A standalone deposit
 * (no non-cash event at its timestamp) IS capital.
 *
 * <p><b>Fixture (USD-only, 2 events):</b>
 * <pre>
 *   day 1 noon: deposit $10,000                  (solo cash event → counts as capital)
 *   day 2 noon: buy 5 VOO @ $100
 *               → VOO IN at noon (non-cash)
 *               → CASH_USD OUT 500 at noon       (trade leg → SKIPPED from invested)
 * </pre>
 *
 * <p><b>Expected:</b>
 * <ul>
 *   <li>USD invested = 10000 (only the deposit counts)</li>
 *   <li>VOO IN itself doesn't show up in the cash walk (different asset)</li>
 *   <li>{@code pnl == dividends + realized + unrealized} still holds</li>
 * </ul>
 *
 * <p><b>Known edge case</b> (not tested here, flagged for future): if a deposit on
 * one currency happens at the exact same timestamp as a non-cash event on a different
 * currency's portfolio, {@code countNonCashEventsAt} would still match and silently
 * skip the deposit as a trade leg. The seeder works around this by stamping deposits
 * at noon and coupons at 09:00, but real users could collide. Worth a future test +
 * fix that scopes the trade-leg detection to "non-cash event on this cash pile's own
 * trade graph" rather than "any non-cash event globally."
 */
public final class TotalsTradeLegSkipTest {

    private TestFixture fx;
    private long vooId;

    @Before
    public void setUp() {
        fx = new TestFixture();
        vooId = fx.addStock("VOO", Currency.USD);
    }

    @After
    public void tearDown() {
        fx.shutdown();
    }

    @Test
    public void onlyStandaloneCashEventsCountAsCapital() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        LocalDate today = d1.plusDays(10);

        fx.repo.recordCashDeposit(Currency.USD, bd("10000"), noon(d1)).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("5"), bd("100"), noon(d1.plusDays(1))).get();

        // Stock price needed so VOO's market value is computed.
        fx.seedStockPrice("VOO", today, "100");

        PortfolioTotals totals = fx.repo.getPortfolioTotals(today).get();
        NativeBucket usd = totals.bucketByCurrency.get(Currency.USD);
        assertNotNull(usd);

        // Deposit counts; trade-leg cash OUT does not.
        assertTrue("USD invested 10000", sameValue(bd("10000"), usd.invested));

        // Sanity: total value = cash on hand (9500) + VOO at cost (5·100 = 500) = 10000.
        // P&L = value − invested = 0 since price hasn't moved.
        assertTrue("USD value 10000", sameValue(bd("10000"), usd.value));
        assertTrue("USD pnl 0",       sameValue(bd("0"),     usd.pnl));

        // Identity (per the bucket contract).
        assertTrue("pnl == dividends + realized + unrealized",
                sameValue(
                        usd.pnl,
                        usd.dividends.add(usd.realizedPnl).add(usd.unrealizedPnl)));
    }
}
