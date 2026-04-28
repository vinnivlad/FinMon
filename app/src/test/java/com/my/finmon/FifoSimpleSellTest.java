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
 * FIFO with a partial sell that consumes one full lot and part of the next.
 *
 * <p><b>Fixture (4 events, USD):</b>
 * <pre>
 *   day 1: deposit $10,000 USD
 *   day 2: buy  3 VOO @ $100  (lot A — purchase cost 300)
 *   day 3: buy  5 VOO @ $120  (lot B — purchase cost 600)
 *   day 4: sell 4 VOO @ $200
 * </pre>
 *
 * <p>FIFO consumes lot A entirely (3 units @ $100) then 1 of lot B (@ $120).
 *
 * <p><b>Expected:</b>
 * <ul>
 *   <li>{@code realizedCostBasis} = 3·100 + 1·120 = 420</li>
 *   <li>{@code realizedProceeds} = 4·200 = 800</li>
 *   <li>realized P&amp;L = 800 − 420 = 380</li>
 *   <li>{@code openQty} = 4 (all from lot B's remainder)</li>
 *   <li>{@code openCostBasis} = 4·120 = 480</li>
 *   <li>One open lot remaining: 4 units @ $120</li>
 * </ul>
 */
public final class FifoSimpleSellTest {

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
    public void partialSellConsumesLotAndPartOfNext() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        fx.repo.recordCashDeposit(Currency.USD, bd("10000"), noon(d1)).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("3"), bd("100"), noon(d1.plusDays(1))).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("5"), bd("120"), noon(d1.plusDays(2))).get();
        fx.repo.recordStockTrade(Side.SELL, vooId, bd("4"), bd("200"), noon(d1.plusDays(3))).get();

        FifoResult fifo = fx.repo.computeFifoCostBasis(vooId, d1.plusDays(3)).get();

        assertTrue("realizedCostBasis 420", sameValue(bd("420"), fifo.realizedCostBasis));
        assertTrue("realizedProceeds 800", sameValue(bd("800"), fifo.realizedProceeds));
        assertTrue("openQty 4", sameValue(bd("4"), fifo.openQty));
        assertTrue("openCostBasis 480", sameValue(bd("480"), fifo.openCostBasis));

        assertEquals("1 open lot left", 1, fifo.openLots.size());
        OpenLot remainder = fifo.openLots.get(0);
        assertTrue("remainder 4 @ 120",
                sameValue(bd("4"), remainder.qty) && sameValue(bd("120"), remainder.unitPrice));
    }
}
