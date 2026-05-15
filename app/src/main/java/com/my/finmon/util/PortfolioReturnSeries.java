package com.my.finmon.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Cumulative simple-return series for a window of portfolio snapshots. For
 * each point in the input, computes
 * <pre>
 *   pct(t) = (pnl(t) − pnl(anchor)) / |invested(t)| × 100
 * </pre>
 * where {@code pnl(x) = value(x) − invested(x)}. The chart is anchored at 0 %
 * on the first input point whose value is positive — earlier points return
 * {@code null} so callers can drop them from the rendered series.
 *
 * <p>The denominator is the <em>current</em> invested capital at each point,
 * never the start-of-window market value. That choice is what keeps the
 * formula dust-immune: even if a currency bucket once held a few cents of
 * rounding remainder, {@code invested(t)} reflects all the money that has been
 * put in by date {@code t}, so the divisor is always full-sized at the period
 * end — which is also the headline number on the totals card.
 *
 * <p>At any point where {@code invested(t) == 0} (capital fully withdrawn or
 * netted out by cross-currency conversions), the per-point return is
 * undefined; the helper carries the previous point's percentage forward so the
 * rendered line doesn't visually jump.
 */
public final class PortfolioReturnSeries {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PortfolioReturnSeries() {}

    /**
     * @return one entry per input point. {@code null} for pre-anchor entries
     *         (where the portfolio hadn't been funded yet in this currency);
     *         from anchor onward each entry is the cumulative simple return
     *         expressed as a {@link BigDecimal} percentage.
     */
    @NonNull
    public static List<BigDecimal> cumulativePct(
            @NonNull List<BigDecimal> values, @NonNull List<BigDecimal> investeds) {
        if (values.size() != investeds.size()) {
            throw new IllegalArgumentException(
                    "values/investeds length mismatch: " + values.size() + " vs " + investeds.size());
        }
        int n = values.size();
        List<BigDecimal> out = new ArrayList<>(n);
        BigDecimal anchorPnl = null;
        BigDecimal lastPct = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal v = values.get(i);
            BigDecimal inv = investeds.get(i);
            if (anchorPnl == null) {
                if (v.signum() > 0) {
                    anchorPnl = v.subtract(inv);
                    out.add(BigDecimal.ZERO);
                } else {
                    out.add(null);
                }
                continue;
            }
            BigDecimal periodPnl = v.subtract(inv).subtract(anchorPnl);
            if (inv.signum() == 0) {
                out.add(lastPct);
                continue;
            }
            BigDecimal pct = periodPnl.divide(inv.abs(), MathContext.DECIMAL64).multiply(HUNDRED);
            lastPct = pct;
            out.add(pct);
        }
        return out;
    }
}
