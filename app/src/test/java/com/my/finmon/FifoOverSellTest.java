package com.my.finmon;

import static com.my.finmon.testing.TestFixture.bd;
import static com.my.finmon.testing.TestFixture.noon;
import static com.my.finmon.testing.TestFixture.sameValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.FifoResult;
import com.my.finmon.data.repository.PortfolioRepository.Side;
import com.my.finmon.testing.TestFixture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;

/**
 * FIFO when a sell exceeds open lots — the queue drains to zero and the excess
 * portion has no cost basis to match against. Production's {@code computeFifo}
 * silently allows this (over-sells "drain" the queue) so the math stays defined;
 * surfacing the over-sell as an error is documented as future work.
 *
 * <p><b>Fixture (3 events, USD):</b>
 * <pre>
 *   day 1: deposit $10,000 USD
 *   day 2: buy  3 VOO @ $100  (only 3 units ever bought)
 *   day 3: sell 5 VOO @ $200  (over-sell by 2)
 * </pre>
 *
 * <p><b>Expected:</b>
 * <ul>
 *   <li>{@code realizedCostBasis} = 3·100 = 300 (only the 3 actually-held units contribute)</li>
 *   <li>{@code realizedProceeds} = 5·200 = 1000 (the full sell amount, even the unmatched 2)</li>
 *   <li>{@code openQty} = 0, {@code openCostBasis} = 0</li>
 *   <li>No open lots</li>
 * </ul>
 *
 * <p>Note: this means the over-sell looks like a 700-realized "win" on paper. The math
 * stays self-consistent; the user-facing fix (refuse to record over-sells, or flag them
 * as data-entry errors) is a separate concern from FIFO arithmetic.
 */
public final class FifoOverSellTest {

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
    public void sellingMoreThanHeldDrainsQueue() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        fx.repo.recordCashDeposit(Currency.USD, bd("10000"), noon(d1)).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("3"), bd("100"), noon(d1.plusDays(1))).get();
        fx.repo.recordStockTrade(Side.SELL, vooId, bd("5"), bd("200"), noon(d1.plusDays(2))).get();

        FifoResult fifo = fx.repo.computeFifoCostBasis(vooId, d1.plusDays(2)).get();

        assertTrue("realizedCostBasis 300", sameValue(bd("300"), fifo.realizedCostBasis));
        assertTrue("realizedProceeds 1000", sameValue(bd("1000"), fifo.realizedProceeds));
        assertTrue("openQty 0", sameValue(bd("0"), fifo.openQty));
        assertTrue("openCostBasis 0", sameValue(bd("0"), fifo.openCostBasis));
        assertEquals("no open lots", 0, fifo.openLots.size());
    }
}
