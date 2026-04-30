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
    SPLIT,
    /**
     * Bond principal repayment (the cash leg). Lives on a {@code CASH_*} pile, with
     * {@code incomeSourceAssetId} pointing back to the redeemed bond. Paired with an
     * {@link #OUT} event on the bond asset (price = face) at the same timestamp,
     * inserted atomically.
     *
     * <p>Distinct from {@link #DIVIDEND} so a same-date coupon and principal don't
     * collide on the {@code (incomeSourceAssetId, date)} dedup key. At most one
     * MATURITY event per bond ever — see {@code EventDao.findMaturityForAsset}.
     */
    MATURITY,

    /**
     * Outgoing leg of an in-brokerage cash conversion (e.g. EUR → USD). Lives on the
     * source {@code CASH_*} pile. Paired with a {@link #CONVERSION_IN} on the target
     * cash pile at the same timestamp, inserted atomically via
     * {@code EventDao.insertTradePair}. {@code amount} is the amount taken out of the
     * source currency; {@code price} = 1; {@code incomeSourceAssetId} is null.
     *
     * <p>Distinct from {@link #OUT} so the per-currency capital walk can recognise
     * conversions by type rather than relying on timestamp pairing — that lets a
     * conversion happening at the same instant as a stock trade not be misclassified
     * as a trade leg. From a per-currency invested perspective: this DECREASES the
     * source currency's invested capital. The paired {@link #CONVERSION_IN} INCREASES
     * the target currency's invested capital. Base-currency invested stays roughly
     * flat (modulo FX drift between the two FX-at-event-date conversions, which is
     * the project's design — see {@code core_goal}).
     */
    CONVERSION_OUT,

    /**
     * Incoming leg of an in-brokerage cash conversion. See {@link #CONVERSION_OUT}.
     * Lives on the target {@code CASH_*} pile. {@code amount} is the amount received
     * in the target currency. The implicit FX rate of the conversion is
     * {@code amount_in / amount_out}; we don't store it separately because each leg
     * is in its own currency.
     */
    CONVERSION_IN
}
