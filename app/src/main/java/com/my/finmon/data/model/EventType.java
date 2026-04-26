package com.my.finmon.data.model;

/**
 * Kind of action a single {@link com.my.finmon.data.entity.EventEntity} represents.
 *
 * <p>{@code IN}/{@code OUT} are cash-flow direction for trades, deposits, withdrawals.
 * {@code DIVIDEND} and {@code SPLIT} are categories — they don't have a flow direction
 * in the {@code IN/OUT} sense and are handled by separate code paths.
 *
 * <p>Adding values here is non-breaking on the storage side: Room serializes via
 * {@code name()}, and existing rows still round-trip cleanly. Reading code branches that
 * inspect this enum must handle the new cases or risk silently dropping events.
 */
public enum EventType {
    /** Asset entering the portfolio: buy stock/bond, deposit cash. */
    IN,
    /** Asset leaving the portfolio: sell stock/bond, withdraw cash. */
    OUT,
    /**
     * Cash inflow attributable to a held asset: stock dividend or bond coupon.
     * Always lives on a {@code CASH_*} pile, with {@code incomeSourceAssetId} pointing
     * back to the source stock/bond. Counted as return-on-investment in P&amp;L, not as
     * new external capital.
     */
    DIVIDEND,
    /**
     * Stock split. Lives on the STOCK asset (no cash leg). {@code amount} stores the
     * split ratio = {@code numerator / denominator} (e.g. 4 for a 4-for-1 forward split,
     * 0.25 for a 1-for-4 reverse split). The FIFO walker multiplies open-lot qty by
     * this ratio and divides per-unit price by it. {@code price} is unused (set to 1).
     *
     * <p>Forward-only: pre-existing IN events are not retroactively scaled. A SPLIT
     * event must be present <em>before</em> any subsequent dividend or sell so the
     * FIFO walk computes correct held quantity.
     */
    SPLIT
}
