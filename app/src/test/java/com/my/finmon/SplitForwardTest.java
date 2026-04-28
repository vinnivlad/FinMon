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
 * Forward 2-for-1 split applied to an open lot, then a sell against the post-split price.
 *
 * <p><b>Fixture (4 events, USD):</b>
 * <pre>
 *   day 1: deposit $10,000 USD
 *   day 2: buy   5 VOO @ $100   (lot — cost basis 500)
 *   day 3: 2-for-1 SPLIT (ratio = 2) — qty doubles, per-unit price halves
 *   day 4: sell  4 VOO @ $60    (post-split price)
 * </pre>
 *
 * <p>After the split the open lot is 10 units @ $50 (cost basis still 500). FIFO on the
 * sell consumes 4 of those 10 at $50 each.
 *
 * <p><b>Expected:</b>
 * <ul>
 *   <li>{@code realizedCostBasis} = 4·50 = 200</li>
 *   <li>{@code realizedProceeds} = 4·60 = 240</li>
 *   <li>realized P&amp;L = 40</li>
 *   <li>{@code openQty} = 6 (10 − 4 sold)</li>
 *   <li>{@code openCostBasis} = 6·50 = 300</li>
 *   <li>One open lot: 6 units @ $50</li>
 * </ul>
 *
 * <p>Note: the split is ingested via {@code ingestStockEvents} so we exercise the same
 * path as the Yahoo auto-ingest (instead of inserting a SPLIT row directly). An empty
 * dividends list keeps the call focused on the split.
 */
public final class SplitForwardTest {

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
    public void twoForOneSplitScalesLot() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        fx.repo.recordCashDeposit(Currency.USD, bd("10000"), noon(d1)).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("5"), bd("100"), noon(d1.plusDays(1))).get();

        // 2-for-1 forward split on day 3.
        fx.repo.ingestStockEvents(
                vooId,
                Collections.<DividendIngest>emptyList(),
                Collections.singletonList(new SplitIngest(noon(d1.plusDays(2)), bd("2")))
        ).get();

        fx.repo.recordStockTrade(Side.SELL, vooId, bd("4"), bd("60"), noon(d1.plusDays(3))).get();

        FifoResult fifo = fx.repo.computeFifoCostBasis(vooId, d1.plusDays(3)).get();

        assertTrue("realizedCostBasis 200", sameValue(bd("200"), fifo.realizedCostBasis));
        assertTrue("realizedProceeds 240", sameValue(bd("240"), fifo.realizedProceeds));
        assertTrue("openQty 6", sameValue(bd("6"), fifo.openQty));
        assertTrue("openCostBasis 300", sameValue(bd("300"), fifo.openCostBasis));

        assertEquals("1 open lot left", 1, fifo.openLots.size());
        OpenLot lot = fifo.openLots.get(0);
        assertTrue("remainder 6 @ 50",
                sameValue(bd("6"), lot.qty) && sameValue(bd("50"), lot.unitPrice));
    }
}
