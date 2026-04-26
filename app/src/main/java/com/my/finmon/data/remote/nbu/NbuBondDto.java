package com.my.finmon.data.remote.nbu;

import java.util.List;

/**
 * Single bond entry in NBU's {@code /depo_securities?json} response.
 *
 * <p>Field shape (verified 2026-04-26):
 * <pre>{@code
 * {
 *   "cpcode":     "UA4000187348",     // ISIN
 *   "nominal":    1000,                // face value, native currency
 *   "auk_proc":   12.5,                // coupon rate, percent (not fraction)
 *   "pgs_date":   "2029-10-12",        // maturity, ISO date
 *   "razm_date":  "2014-10-31",        // placement / issue
 *   "cptype":     "DCP",               // bond type code
 *   "cpdescr":    "Довгострокові",     // human-readable description
 *   "pay_period": 182,                 // days between coupon payments
 *   "val_code":   "UAH",               // currency
 *   "emit_okpo":  "00013480",          // issuer EDRPOU
 *   "emit_name":  "Міністерство фінансів України",
 *   "payments":   [...]                // schedule, see Payment below
 * }
 * }</pre>
 *
 * <p>Each payment row carries {@code pay_type}: {@code "1"} = coupon,
 * {@code "2"} = principal repayment (only on the maturity date).
 */
public final class NbuBondDto {
    public String cpcode;
    public Double nominal;
    public Double auk_proc;
    public String pgs_date;
    public String razm_date;
    public String cptype;
    public String cpdescr;
    public Integer pay_period;
    public String val_code;
    public String emit_okpo;
    public String emit_name;
    public List<Payment> payments;

    public static final class Payment {
        public String pay_date;
        /** {@code "1"} = coupon, {@code "2"} = principal repayment at maturity. */
        public String pay_type;
        /** Per-bond-unit amount in {@link #val_code}. */
        public Double pay_val;
    }
}
