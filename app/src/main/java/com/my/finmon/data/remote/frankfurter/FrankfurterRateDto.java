package com.my.finmon.data.remote.frankfurter;

/**
 * One row in the Frankfurter v2 /rates response. The response is a flat JSON array —
 * same shape whether we ask for today or a date range, whether we ask for one currency
 * or all of them.
 *
 * Example: {@code {"date":"2026-04-21","base":"EUR","quote":"UAH","rate":51.893}}
 *
 * Note: {@code rate} is a double, not BigDecimal. FX quotes Frankfurter serves have
 * 4–6 decimals of precision, well inside IEEE 754 double's 15-digit range. Callers that
 * need BigDecimal should use {@code BigDecimal.valueOf(rate)}, which uses the shortest
 * decimal representation of the double and preserves the originally-printed value.
 */
public final class FrankfurterRateDto {
    public String date;
    public String base;
    public String quote;
    public double rate;
}
