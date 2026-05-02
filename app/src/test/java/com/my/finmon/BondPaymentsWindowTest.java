package com.my.finmon;

import static com.my.finmon.testing.TestFixture.bd;
import static com.my.finmon.testing.TestFixture.nineAm;
import static com.my.finmon.testing.TestFixture.noon;
import static com.my.finmon.testing.TestFixture.sameValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.my.finmon.data.model.Currency;
import com.my.finmon.data.model.EventType;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPayment;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPaymentsResult;
import com.my.finmon.data.repository.PortfolioRepository.Side;
import com.my.finmon.testing.TestFixture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Math-correctness tests for {@code getBondPaymentsInWindow} — the past-payments
 * leg of the Bonds screen's Expected Payments card and Calendar tab.
 *
 * <p>Tests build their own past payments via {@code recordCouponPayment} /
 * {@code recordBondMaturity} rather than going through NBU. The repo's NBU
 * client is null (TestFixture default) so the future-projection leg is a no-op
 * in unit tests — we cover that via the integration of the past leg only.
 *
 * <p>What's pinned:
 * <ul>
 *   <li>Native-currency totals — sum of in-window DIVIDEND/MATURITY amounts per currency.</li>
 *   <li>Cross-currency totals — each row converted via FX into every display currency.</li>
 *   <li>Base total — sum in {@code BASE_CURRENCY} (USD).</li>
 *   <li>Currency filter narrows to one bond's currency.</li>
 *   <li>Stock dividends + cash deposits don't leak into bond totals.</li>
 *   <li>Window-boundary inclusivity (in-window vs out-of-window dates).</li>
 *   <li>{@code hasFxGaps} when an FX rate is missing for the row's date.</li>
 * </ul>
 */
public final class BondPaymentsWindowTest {

    private TestFixture fx;
    private long ovdpUahId;     // UAH bond
    private long ovdpUsdId;     // USD bond — to test currency filter and multi-currency totals
    private long vooId;         // STOCK — to verify it doesn't pollute bond totals

    @Before
    public void setUp() {
        fx = new TestFixture();
        ovdpUahId = fx.addBond("OVDP-UAH", Currency.UAH,
                bd("1000"), bd("12"), LocalDate.of(2027, 1, 1));
        ovdpUsdId = fx.addBond("OVDP-USD", Currency.USD,
                bd("1000"), bd("4"), LocalDate.of(2027, 6, 1));
        vooId = fx.addStock("VOO", Currency.USD);
    }

    @After
    public void tearDown() {
        fx.shutdown();
    }

    /** UAH bond pays 3 coupons inside the window — totals reflect only those rows. */
    @Test
    public void pastCouponsSumNativeCurrencyTotal() throws Exception {
        // Cash + buy so the bond actually has open lots (also lets recordCoupon land cleanly).
        fx.repo.recordCashDeposit(Currency.UAH, bd("12000"), noon(LocalDate.of(2025, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUahId, bd("10"), bd("1000"),
                noon(LocalDate.of(2025, 1, 2))).get();

        // Coupons: 60 (Mar), 60 (Jun), 60 (Sep) all 2025.
        fx.repo.recordCouponPayment(ovdpUahId, bd("60"), Currency.UAH,
                nineAm(LocalDate.of(2025, 3, 1))).get();
        fx.repo.recordCouponPayment(ovdpUahId, bd("60"), Currency.UAH,
                nineAm(LocalDate.of(2025, 6, 1))).get();
        fx.repo.recordCouponPayment(ovdpUahId, bd("60"), Currency.UAH,
                nineAm(LocalDate.of(2025, 9, 1))).get();

        // Window: full year 2025.
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        LocalDate today = LocalDate.of(2026, 1, 1);  // window is fully in the past

        ExpectedPaymentsResult r = fx.repo.getBondPaymentsInWindow(from, to, today, null).get();

        assertEquals("3 coupon rows", 3, r.payments.size());
        for (ExpectedPayment p : r.payments) {
            assertTrue("paid", p.paid);
            assertEquals(EventType.DIVIDEND, p.type);
            assertEquals(Currency.UAH, p.currency);
        }
        assertTrue("UAH native total = 180",
                sameValue(bd("180"), r.totalsByCurrency.get(Currency.UAH)));
    }

    /**
     * Cross-currency totals: each row converted via FX into every display currency.
     * One UAH coupon (50 UAH) + one USD coupon (10 USD); FX UAH→USD = 0.025, USD→UAH = 40.
     * Display USD total = 50·0.025 + 10·1 = 11.25 USD; display UAH = 50·1 + 10·40 = 450 UAH.
     */
    @Test
    public void crossCurrencyDisplayTotalsAggregateAcrossBonds() throws Exception {
        // Seed FX rates available as of payment dates.
        fx.seedFx(Currency.UAH, Currency.USD, LocalDate.of(2025, 1, 1), "0.025");
        fx.seedFx(Currency.USD, Currency.UAH, LocalDate.of(2025, 1, 1), "40");
        fx.seedFx(Currency.UAH, Currency.EUR, LocalDate.of(2025, 1, 1), "0.022");
        fx.seedFx(Currency.USD, Currency.EUR, LocalDate.of(2025, 1, 1), "0.9");
        fx.seedFx(Currency.EUR, Currency.USD, LocalDate.of(2025, 1, 1), "1.1");
        fx.seedFx(Currency.EUR, Currency.UAH, LocalDate.of(2025, 1, 1), "44");
        fx.seedFxIdentity(Currency.USD, LocalDate.of(2025, 1, 1));
        fx.seedFxIdentity(Currency.UAH, LocalDate.of(2025, 1, 1));
        fx.seedFxIdentity(Currency.EUR, LocalDate.of(2025, 1, 1));

        fx.repo.recordCashDeposit(Currency.UAH, bd("60000"), noon(LocalDate.of(2025, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUahId, bd("10"), bd("1000"),
                noon(LocalDate.of(2025, 1, 2))).get();
        fx.repo.recordCashDeposit(Currency.USD, bd("2000"), noon(LocalDate.of(2025, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUsdId, bd("1"), bd("1000"),
                noon(LocalDate.of(2025, 1, 2))).get();

        fx.repo.recordCouponPayment(ovdpUahId, bd("50"), Currency.UAH,
                nineAm(LocalDate.of(2025, 6, 1))).get();
        fx.repo.recordCouponPayment(ovdpUsdId, bd("10"), Currency.USD,
                nineAm(LocalDate.of(2025, 6, 1))).get();

        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        LocalDate today = LocalDate.of(2026, 1, 1);

        ExpectedPaymentsResult r = fx.repo.getBondPaymentsInWindow(from, to, today, null).get();

        assertEquals("2 rows", 2, r.payments.size());
        assertTrue("UAH native = 50",
                sameValue(bd("50"), r.totalsByCurrency.get(Currency.UAH)));
        assertTrue("USD native = 10",
                sameValue(bd("10"), r.totalsByCurrency.get(Currency.USD)));

        // Display USD: 50 UAH × 0.025 + 10 USD × 1 = 11.25
        assertTrue("display USD = 11.25",
                sameValue(bd("11.25"), r.totalsByDisplayCurrency.get(Currency.USD)));
        // Display UAH: 50 UAH × 1 + 10 USD × 40 = 450
        assertTrue("display UAH = 450",
                sameValue(bd("450"), r.totalsByDisplayCurrency.get(Currency.UAH)));

        // Base (USD) total matches the display USD total.
        assertTrue("base (USD) = 11.25",
                sameValue(bd("11.25"), r.totalInBase));
        assertEquals(Currency.USD, r.baseCurrency);
        assertFalse("no fx gaps", r.hasFxGaps);
    }

    /** Currency filter narrows to bonds in that currency only. */
    @Test
    public void currencyFilterNarrowsToOneBond() throws Exception {
        fx.seedFx(Currency.UAH, Currency.USD, LocalDate.of(2025, 1, 1), "0.025");
        fx.seedFx(Currency.USD, Currency.UAH, LocalDate.of(2025, 1, 1), "40");
        fx.seedFxIdentity(Currency.USD, LocalDate.of(2025, 1, 1));
        fx.seedFxIdentity(Currency.UAH, LocalDate.of(2025, 1, 1));
        fx.seedFxIdentity(Currency.EUR, LocalDate.of(2025, 1, 1));

        fx.repo.recordCashDeposit(Currency.UAH, bd("60000"), noon(LocalDate.of(2025, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUahId, bd("10"), bd("1000"),
                noon(LocalDate.of(2025, 1, 2))).get();
        fx.repo.recordCashDeposit(Currency.USD, bd("2000"), noon(LocalDate.of(2025, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUsdId, bd("1"), bd("1000"),
                noon(LocalDate.of(2025, 1, 2))).get();

        fx.repo.recordCouponPayment(ovdpUahId, bd("50"), Currency.UAH,
                nineAm(LocalDate.of(2025, 6, 1))).get();
        fx.repo.recordCouponPayment(ovdpUsdId, bd("10"), Currency.USD,
                nineAm(LocalDate.of(2025, 6, 1))).get();

        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        LocalDate today = LocalDate.of(2026, 1, 1);

        ExpectedPaymentsResult r = fx.repo.getBondPaymentsInWindow(
                from, to, today, Currency.UAH).get();

        assertEquals("only UAH row", 1, r.payments.size());
        assertEquals(Currency.UAH, r.payments.get(0).currency);
        assertTrue("UAH native total = 50",
                sameValue(bd("50"), r.totalsByCurrency.get(Currency.UAH)));
        // USD native total absent (or zero) — no USD row contributed.
        BigDecimal usdNative = r.totalsByCurrency.get(Currency.USD);
        assertTrue("USD native is zero/null",
                usdNative == null || usdNative.signum() == 0);
    }

    /**
     * Stock dividends and cash deposits live in the same event log but must NOT
     * leak into bond totals. Bond payments are bond-source only — DIVIDEND events
     * sourced from STOCKs filter out at the asset-type level.
     */
    @Test
    public void stockDividendsAndCashEventsDoNotPollute() throws Exception {
        // Stock dividend that should be IGNORED.
        fx.repo.recordCashDeposit(Currency.USD, bd("5000"), noon(LocalDate.of(2025, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, vooId, bd("10"), bd("100"),
                noon(LocalDate.of(2025, 1, 2))).get();
        fx.repo.recordDividendPayment(vooId, bd("999"), Currency.USD,
                nineAm(LocalDate.of(2025, 6, 1))).get();

        // Bond coupon that should COUNT.
        fx.repo.recordCashDeposit(Currency.UAH, bd("12000"), noon(LocalDate.of(2025, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUahId, bd("10"), bd("1000"),
                noon(LocalDate.of(2025, 1, 2))).get();
        fx.repo.recordCouponPayment(ovdpUahId, bd("60"), Currency.UAH,
                nineAm(LocalDate.of(2025, 6, 1))).get();

        // Extra cash withdraw / deposit — pure capital flows, not income.
        fx.repo.recordCashWithdrawal(Currency.UAH, bd("100"),
                noon(LocalDate.of(2025, 7, 1))).get();

        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        LocalDate today = LocalDate.of(2026, 1, 1);

        ExpectedPaymentsResult r = fx.repo.getBondPaymentsInWindow(from, to, today, null).get();

        // Only the 60 UAH bond coupon. The 999 USD stock dividend and the cash
        // withdrawal must not appear.
        assertEquals("one bond row", 1, r.payments.size());
        assertEquals(ovdpUahId, r.payments.get(0).bondAssetId);
        assertTrue("UAH native = 60",
                sameValue(bd("60"), r.totalsByCurrency.get(Currency.UAH)));
        BigDecimal usdNative = r.totalsByCurrency.get(Currency.USD);
        assertTrue("USD native zero/null (stock div ignored)",
                usdNative == null || usdNative.signum() == 0);
    }

    /**
     * Window boundary: payments outside [from, to] don't count. Inclusive on both
     * ends — payments on {@code from} and {@code to} are kept.
     */
    @Test
    public void windowBoundariesAreInclusive() throws Exception {
        fx.repo.recordCashDeposit(Currency.UAH, bd("12000"), noon(LocalDate.of(2024, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUahId, bd("10"), bd("1000"),
                noon(LocalDate.of(2024, 1, 2))).get();

        // Three coupons: one before window, one on lower boundary, one on upper boundary,
        // one after window.
        fx.repo.recordCouponPayment(ovdpUahId, bd("10"), Currency.UAH,
                nineAm(LocalDate.of(2024, 12, 31))).get();   // out (before)
        fx.repo.recordCouponPayment(ovdpUahId, bd("20"), Currency.UAH,
                nineAm(LocalDate.of(2025, 1, 1))).get();     // in (lower bound)
        fx.repo.recordCouponPayment(ovdpUahId, bd("30"), Currency.UAH,
                nineAm(LocalDate.of(2025, 12, 31))).get();   // in (upper bound)
        fx.repo.recordCouponPayment(ovdpUahId, bd("40"), Currency.UAH,
                nineAm(LocalDate.of(2026, 1, 1))).get();     // out (after)

        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        LocalDate today = LocalDate.of(2026, 6, 1);  // window fully past

        ExpectedPaymentsResult r = fx.repo.getBondPaymentsInWindow(from, to, today, null).get();

        assertEquals("only the two boundary coupons", 2, r.payments.size());
        assertTrue("UAH native = 50 (20 + 30)",
                sameValue(bd("50"), r.totalsByCurrency.get(Currency.UAH)));
    }

    /** A MATURITY event in the window contributes its principal-return amount. */
    @Test
    public void maturityEventCountsAsPastPayment() throws Exception {
        fx.repo.recordCashDeposit(Currency.UAH, bd("12000"), noon(LocalDate.of(2024, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUahId, bd("10"), bd("1000"),
                noon(LocalDate.of(2024, 1, 2))).get();
        fx.repo.recordCouponPayment(ovdpUahId, bd("60"), Currency.UAH,
                nineAm(LocalDate.of(2025, 1, 1))).get();
        // Redeem on a date inside the window. recordBondMaturity stamps the event
        // at 09:00 on the redemption date and writes amount = face × openQty.
        fx.repo.recordBondMaturity(ovdpUahId, LocalDate.of(2025, 6, 1)).get();

        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        LocalDate today = LocalDate.of(2026, 6, 1);

        ExpectedPaymentsResult r = fx.repo.getBondPaymentsInWindow(from, to, today, null).get();

        // 1 coupon + 1 maturity = 2 rows.
        assertEquals("coupon + maturity", 2, r.payments.size());
        boolean foundMaturity = false;
        for (ExpectedPayment p : r.payments) {
            if (p.type == EventType.MATURITY) {
                foundMaturity = true;
                assertTrue("principal = 10000 (10 × 1000 face)",
                        sameValue(bd("10000"), p.amount));
            }
        }
        assertTrue("MATURITY row present", foundMaturity);
        // 60 coupon + 10000 principal = 10060.
        assertTrue("UAH native total = 10060",
                sameValue(bd("10060"), r.totalsByCurrency.get(Currency.UAH)));
    }

    /**
     * When an FX rate is missing for the row's date, the cross-currency totals
     * skip that contribution and {@code hasFxGaps} flips true. Native total is
     * unaffected.
     */
    @Test
    public void missingFxRateFlagsHasFxGaps() throws Exception {
        // Identity rates only — no UAH↔USD/EUR pairs seeded.
        fx.seedFxIdentity(Currency.USD, LocalDate.of(2025, 1, 1));
        fx.seedFxIdentity(Currency.UAH, LocalDate.of(2025, 1, 1));
        fx.seedFxIdentity(Currency.EUR, LocalDate.of(2025, 1, 1));

        fx.repo.recordCashDeposit(Currency.UAH, bd("12000"), noon(LocalDate.of(2025, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUahId, bd("10"), bd("1000"),
                noon(LocalDate.of(2025, 1, 2))).get();
        fx.repo.recordCouponPayment(ovdpUahId, bd("60"), Currency.UAH,
                nineAm(LocalDate.of(2025, 6, 1))).get();

        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        LocalDate today = LocalDate.of(2026, 1, 1);

        ExpectedPaymentsResult r = fx.repo.getBondPaymentsInWindow(from, to, today, null).get();

        assertEquals(1, r.payments.size());
        assertTrue("UAH native total = 60",
                sameValue(bd("60"), r.totalsByCurrency.get(Currency.UAH)));
        // Same currency display total still works (UAH→UAH is identity).
        assertTrue("UAH display = 60",
                sameValue(bd("60"), r.totalsByDisplayCurrency.get(Currency.UAH)));
        // No UAH→USD or UAH→EUR rate seeded → those display totals stay at 0
        // and hasFxGaps flips on.
        BigDecimal usdDisplay = r.totalsByDisplayCurrency.get(Currency.USD);
        assertTrue("USD display stays at 0 (no FX)",
                usdDisplay != null && usdDisplay.signum() == 0);
        assertTrue("hasFxGaps", r.hasFxGaps);
    }

    /**
     * Future-only window with no NBU client returns an empty result — the past
     * leg is empty (no events match) and the future leg is gated on nbuClient.
     */
    @Test
    public void futureOnlyWindowWithoutNbuIsEmpty() throws Exception {
        fx.repo.recordCashDeposit(Currency.UAH, bd("12000"), noon(LocalDate.of(2024, 1, 1))).get();
        fx.repo.recordStockTrade(Side.BUY, ovdpUahId, bd("10"), bd("1000"),
                noon(LocalDate.of(2024, 1, 2))).get();
        fx.repo.recordCouponPayment(ovdpUahId, bd("60"), Currency.UAH,
                nineAm(LocalDate.of(2025, 6, 1))).get();

        LocalDate today = LocalDate.of(2026, 1, 1);
        LocalDate from = today;
        LocalDate to = today.plusYears(2);

        ExpectedPaymentsResult r = fx.repo.getBondPaymentsInWindow(from, to, today, null).get();

        assertNotNull(r);
        assertTrue("no rows when window is fully in the future and NBU is offline",
                r.payments.isEmpty());
        BigDecimal uah = r.totalsByCurrency.get(Currency.UAH);
        assertTrue("UAH native total zero/null", uah == null || uah.signum() == 0);
    }
}
