package com.my.finmon;

import static com.my.finmon.testing.TestFixture.bd;
import static com.my.finmon.testing.TestFixture.nineAm;
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
 * Per-currency P&amp;L identity: {@code pnl == dividends + realized + unrealized}.
 *
 * <p><b>Fixture (USD-only, 5 events):</b>
 * <pre>
 *   day 1: deposit $10,000                       (capital)
 *   day 2: buy  5 VOO @ $100                     (cost 500; trade leg cash OUT skipped)
 *   day 3: sell 2 VOO @ $130                     (realized = 2·(130−100) = +60; proceeds 260)
 *   day 4: $100 dividend payment                 (DIVIDEND, source = VOO)
 *   today: VOO stock price = $120
 * </pre>
 *
 * <p>State today:
 * <ul>
 *   <li>3 VOO open at avg cost 100 → openCostBasis 300; market value 3·120 = 360</li>
 *   <li>CASH_USD balance: 10000 − 500 + 260 + 100 = 9860</li>
 *   <li>Per-currency USD bucket: value = 360 + 9860 = 10220, invested = 10000</li>
 *   <li>pnl (= value − invested) = 220</li>
 *   <li>dividends = 100, realized = 60, unrealized = 360 − 300 = 60</li>
 *   <li>Identity: 100 + 60 + 60 = 220 ✓</li>
 * </ul>
 */
public final class TotalsIdentityTest {

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
    public void perCurrencyIdentityHolds() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        LocalDate today = d1.plusDays(10);

        fx.repo.recordCashDeposit(Currency.USD, bd("10000"), noon(d1)).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("5"), bd("100"), noon(d1.plusDays(1))).get();
        fx.repo.recordStockTrade(Side.SELL, vooId, bd("2"), bd("130"), noon(d1.plusDays(2))).get();
        fx.repo.recordDividendPayment(vooId, bd("100"), Currency.USD, nineAm(d1.plusDays(3))).get();

        // Stock price for the as-of date — needed so VOO's marketValue is non-null.
        fx.seedStockPrice("VOO", today, "120");

        PortfolioTotals totals = fx.repo.getPortfolioTotals(today).get();

        NativeBucket usd = totals.bucketByCurrency.get(Currency.USD);
        assertNotNull("USD bucket present", usd);

        assertTrue("USD value 10220",   sameValue(bd("10220"), usd.value));
        assertTrue("USD invested 10000", sameValue(bd("10000"), usd.invested));
        assertTrue("USD pnl 220",       sameValue(bd("220"),   usd.pnl));
        assertTrue("USD dividends 100", sameValue(bd("100"),   usd.dividends));
        assertTrue("USD realized 60",   sameValue(bd("60"),    usd.realizedPnl));
        assertTrue("USD unrealized 60", sameValue(bd("60"),    usd.unrealizedPnl));

        // The headline identity — drives the bucket card on the breakdown screen.
        assertTrue("pnl == dividends + realized + unrealized",
                sameValue(
                        usd.pnl,
                        usd.dividends.add(usd.realizedPnl).add(usd.unrealizedPnl)));
    }
}
