package com.my.finmon;

import static com.my.finmon.testing.TestFixture.bd;
import static com.my.finmon.testing.TestFixture.noon;
import static com.my.finmon.testing.TestFixture.sameValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.DividendIngest;
import com.my.finmon.data.repository.PortfolioRepository.FifoResult;
import com.my.finmon.data.repository.PortfolioRepository.OpenLot;
import com.my.finmon.data.repository.PortfolioRepository.Side;
import com.my.finmon.data.repository.PortfolioRepository.SplitIngest;
import com.my.finmon.testing.TestFixture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Collections;

/**
 * Forward 2-for-1 split that happens BETWEEN two buys, with a later sell that
 * crosses both lots. Hardens the split path against bugs that only show up when
 * lots have different split histories.
 *
 * <p><b>Fixture (5 events, USD):</b>
 * <pre>
 *   day 1: deposit $20,000 USD
 *   day 2: buy   5 VOO @ $100              (lot A — basis 500)
 *   day 3: 2-for-1 SPLIT (ratio = 2)       — lot A becomes 10 @ $50
 *   day 4: buy   4 VOO @ $80               (lot B — basis 320, post-split price)
 *   day 5: sell 12 VOO @ $90               (consumes all 10 of A, then 2 of B)
 * </pre>
 *
 * <p>Lot B is recorded AFTER the split, so it should not be re-scaled — its qty
 * stays at 4 and per-unit price at $80. The bug this would catch is a
 * lot-walker that retroactively applies the split to every lot regardless of
 * timestamp.
 *
 * <p><b>Expected:</b>
 * <ul>
 *   <li>Sell consumes 10 of A (basis 10·50 = 500, proceeds 10·90 = 900)
 *       then 2 of B (basis 2·80 = 160, proceeds 2·90 = 180).</li>
 *   <li>{@code realizedCostBasis} = 660</li>
 *   <li>{@code realizedProceeds} = 1080</li>
 *   <li>realized P&amp;L = 420</li>
 *   <li>{@code openQty} = 2 (the remainder of B)</li>
 *   <li>{@code openCostBasis} = 2·80 = 160</li>
 *   <li>One open lot remaining: 2 units @ $80 (lot B's tail)</li>
 * </ul>
 */
public final class SplitMultiLotTest {

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
    public void splitOnlyAffectsLotsBeforeIt() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        fx.repo.recordCashDeposit(Currency.USD, bd("20000"), noon(d1)).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("5"), bd("100"), noon(d1.plusDays(1))).get();

        // 2-for-1 forward split between the two buys.
        fx.repo.ingestStockEvents(
                vooId,
                Collections.<DividendIngest>emptyList(),
                Collections.singletonList(new SplitIngest(noon(d1.plusDays(2)), bd("2")))
        ).get();

        // Lot B — bought AFTER the split, must keep its post-split quote intact.
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("4"), bd("80"), noon(d1.plusDays(3))).get();

        // Sell crosses both lots: 10 from A, 2 from B.
        fx.repo.recordStockTrade(Side.SELL, vooId, bd("12"), bd("90"), noon(d1.plusDays(4))).get();

        FifoResult fifo = fx.repo.computeFifoCostBasis(vooId, d1.plusDays(4)).get();

        assertTrue("realizedCostBasis 660", sameValue(bd("660"), fifo.realizedCostBasis));
        assertTrue("realizedProceeds 1080", sameValue(bd("1080"), fifo.realizedProceeds));
        assertTrue("openQty 2", sameValue(bd("2"), fifo.openQty));
        assertTrue("openCostBasis 160", sameValue(bd("160"), fifo.openCostBasis));

        assertEquals("1 open lot left (lot B tail)", 1, fifo.openLots.size());
        OpenLot tail = fifo.openLots.get(0);
        assertTrue("lot B tail: 2 @ $80",
                sameValue(bd("2"), tail.qty) && sameValue(bd("80"), tail.unitPrice));
    }
}
