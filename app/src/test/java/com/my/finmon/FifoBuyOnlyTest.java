package com.my.finmon;

import static com.my.finmon.testing.TestFixture.bd;
import static com.my.finmon.testing.TestFixture.noon;
import static com.my.finmon.testing.TestFixture.sameValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.FifoResult;
import com.my.finmon.data.repository.PortfolioRepository.OpenLot;
import com.my.finmon.data.repository.PortfolioRepository.Side;
import com.my.finmon.testing.TestFixture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;

/**
 * FIFO with no sells — every buy stays open, in the order it was bought.
 *
 * <p><b>Fixture (4 events, USD):</b>
 * <pre>
 *   day 1: deposit $10,000 USD
 *   day 2: buy 3 VOO @ $100  (lot A)
 *   day 3: buy 5 VOO @ $120  (lot B)
 *   day 4: buy 2 VOO @ $140  (lot C)
 * </pre>
 *
 * <p><b>Expected:</b>
 * <ul>
 *   <li>{@code openQty} = 3 + 5 + 2 = 10</li>
 *   <li>{@code openCostBasis} = 3·100 + 5·120 + 2·140 = 300 + 600 + 280 = 1180</li>
 *   <li>{@code realizedCostBasis} = 0, {@code realizedProceeds} = 0</li>
 *   <li>{@code openLots} = three lots in buy order, each with its original qty/price</li>
 * </ul>
 */
public final class FifoBuyOnlyTest {

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
    public void threeBuysAllOpen() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        fx.repo.recordCashDeposit(Currency.USD, bd("10000"), noon(d1)).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("3"), bd("100"), noon(d1.plusDays(1))).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("5"), bd("120"), noon(d1.plusDays(2))).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("2"), bd("140"), noon(d1.plusDays(3))).get();

        FifoResult fifo = fx.repo.computeFifoCostBasis(vooId, d1.plusDays(3)).get();

        assertTrue("openQty 10", sameValue(bd("10"), fifo.openQty));
        assertTrue("openCostBasis 1180", sameValue(bd("1180"), fifo.openCostBasis));
        assertTrue("no realized cost", sameValue(bd("0"), fifo.realizedCostBasis));
        assertTrue("no realized proceeds", sameValue(bd("0"), fifo.realizedProceeds));

        assertEquals("3 open lots", 3, fifo.openLots.size());
        OpenLot a = fifo.openLots.get(0), b = fifo.openLots.get(1), c = fifo.openLots.get(2);
        assertTrue("lot A qty 3 @ 100",
                sameValue(bd("3"), a.qty) && sameValue(bd("100"), a.unitPrice));
        assertTrue("lot B qty 5 @ 120",
                sameValue(bd("5"), b.qty) && sameValue(bd("120"), b.unitPrice));
        assertTrue("lot C qty 2 @ 140",
                sameValue(bd("2"), c.qty) && sameValue(bd("140"), c.unitPrice));
    }
}
