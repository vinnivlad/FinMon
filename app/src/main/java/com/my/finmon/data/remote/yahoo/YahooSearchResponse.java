package com.my.finmon.data.remote.yahoo;

import java.util.List;

/**
 * Top-level shape of the Yahoo Finance v1 search response.
 * <p>
 * Example URL: {@code /v1/finance/search?q=VOO}
 * <p>
 * Example payload (trimmed):
 * <pre>{@code
 * {
 *   "quotes": [
 *     {
 *       "symbol":     "VOO",
 *       "shortname":  "Vanguard 500 Index Fund",
 *       "longname":   "Vanguard S&P 500 ETF",
 *       "exchange":   "NYQ",
 *       "exchDisp":   "NYSEArca",
 *       "quoteType":  "ETF",
 *       "typeDisp":   "ETF"
 *     },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>The {@code quotes} array also contains entries for currencies, indices, futures and
 * options. Callers should filter by {@code quoteType} ({@code EQUITY}/{@code ETF})
 * before showing results to the user.
 */
public final class YahooSearchResponse {
    public List<Quote> quotes;

    public static final class Quote {
        public String symbol;
        public String shortname;
        public String longname;
        public String exchange;
        public String exchDisp;
        public String quoteType;
        public String typeDisp;
    }
}
