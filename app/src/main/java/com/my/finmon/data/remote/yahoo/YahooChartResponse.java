package com.my.finmon.data.remote.yahoo;

import java.util.List;
import java.util.Map;

/**
 * Top-level shape of the Yahoo Finance v8 chart response.
 * <p>
 * Example URL: {@code /v8/finance/chart/VOO?interval=1d&period1=1700000000&period2=1700100000}
 * <p>
 * Successful payload (trimmed):
 * <pre>{@code
 * {
 *   "chart": {
 *     "result": [{
 *       "meta":   { "currency": "USD", "symbol": "VOO", ... },
 *       "timestamp": [1700000000, 1700086400, ...],
 *       "indicators": {
 *         "quote":    [{ "close": [350.12, 351.04, ...], ... }],
 *         "adjclose": [{ "adjclose": [349.80, 350.71, ...] }]
 *       },
 *       "events": {                           // present only when ?events= passed
 *         "dividends": { "1700000000": { "amount": 1.585, "date": 1700000000 } },
 *         "splits":    { "1700000000": { "date": 1700000000, "numerator": 4, "denominator": 1, "splitRatio": "4:1" } }
 *       }
 *     }],
 *     "error": null
 *   }
 * }
 * }</pre>
 *
 * Phase 1 (price-only) only reads {@code timestamp} and {@code indicators.quote[0].close}.
 * The events fields are wired up here so phase 2 (dividend/split ingestion) doesn't have
 * to revisit Moshi's class graph.
 */
public final class YahooChartResponse {
    public Chart chart;

    public static final class Chart {
        public List<Result> result;
        public Error error;
    }

    public static final class Result {
        public Meta meta;
        public List<Long> timestamp;
        public Indicators indicators;
        public Events events;
    }

    public static final class Meta {
        public String currency;
        public String symbol;
        public String exchangeName;
        public String instrumentType;
    }

    public static final class Indicators {
        public List<Quote> quote;
        public List<AdjClose> adjclose;
    }

    public static final class Quote {
        /** Daily close, NOT split-adjusted. Use this — see project notes on splits. */
        public List<Double> close;
        public List<Double> open;
        public List<Double> high;
        public List<Double> low;
        public List<Long> volume;
    }

    public static final class AdjClose {
        public List<Double> adjclose;
    }

    public static final class Events {
        /** Keyed by epoch-second string (Yahoo's quirk). */
        public Map<String, Dividend> dividends;
        public Map<String, Split> splits;
    }

    public static final class Dividend {
        public Double amount;
        public Long date;
    }

    public static final class Split {
        public Long date;
        public Double numerator;
        public Double denominator;
        public String splitRatio;
    }

    public static final class Error {
        public String code;
        public String description;
    }
}
