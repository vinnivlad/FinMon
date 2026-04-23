package com.my.finmon.data.repository;

import androidx.annotation.NonNull;

import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.ExchangeRateEntity;
import com.my.finmon.data.entity.StockPriceEntity;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.remote.frankfurter.FrankfurterClient;
import com.my.finmon.data.remote.frankfurter.FrankfurterRateDto;
import com.my.finmon.data.remote.stooq.StooqClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Fetches market data from the two remote sources and normalizes it into Room rows.
 * Does no P&L math — that's PortfolioRepository's job. Schedules are driven by the
 * sync worker (to be built in step 4).
 *
 * FX storage strategy (decided 2026-04-21): precompute and store all directed pairs
 * among {USD, EUR, UAH} per date. Frankfurter delivers EUR-based rates; we invert and
 * cross-multiply once here so every downstream read is a single keyed lookup.
 */
public final class MarketDataRepository {

    /** Enough significant digits for FX without producing recurring-decimal bloat. */
    private static final MathContext FX_MC = new MathContext(12, RoundingMode.HALF_UP);

    private final StooqClient stooqClient;
    private final FrankfurterClient frankfurterClient;
    private final StockPriceDao stockPriceDao;
    private final ExchangeRateDao exchangeRateDao;
    private final ExecutorService executor;

    public MarketDataRepository(
            @NonNull StooqClient stooqClient,
            @NonNull FrankfurterClient frankfurterClient,
            @NonNull StockPriceDao stockPriceDao,
            @NonNull ExchangeRateDao exchangeRateDao,
            @NonNull ExecutorService executor) {
        this.stooqClient = stooqClient;
        this.frankfurterClient = frankfurterClient;
        this.stockPriceDao = stockPriceDao;
        this.exchangeRateDao = exchangeRateDao;
        this.executor = executor;
    }

    /**
     * Fetches daily closes for {@code stooqTicker} (exchange-suffixed, e.g. {@code aapl.us})
     * in [{@code from}, {@code to}] inclusive and upserts them into {@code stock_price}.
     * Rows are keyed by {@code storageTicker} (the domain symbol, e.g. {@code AAPL}) so
     * they line up with {@code asset.ticker}. Returns the number of rows written.
     */
    @NonNull
    public Future<Integer> fetchAndStoreStockPrices(
            @NonNull String stooqTicker,
            @NonNull String storageTicker,
            @NonNull LocalDate from,
            @NonNull LocalDate to) {
        return executor.submit(() -> {
            List<StockPriceEntity> rows = stooqClient.fetchDaily(stooqTicker, storageTicker, from, to);
            if (!rows.isEmpty()) {
                stockPriceDao.upsertAll(rows);
            }
            return rows.size();
        });
    }

    /**
     * Fetches EUR-based rates from Frankfurter (provider NBU) for [{@code from}, {@code to}]
     * inclusive, computes every directed pair among {USD, EUR, UAH}, and upserts the
     * resulting {@code ExchangeRateEntity} rows. Returns the number of rows written —
     * 6 per date that has both USD and UAH quotes published.
     */
    @NonNull
    public Future<Integer> fetchAndStoreFxRates(@NonNull LocalDate from, @NonNull LocalDate to) {
        return executor.submit(() -> {
            List<FrankfurterRateDto> raw = frankfurterClient.fetchRange(from, to);
            List<ExchangeRateEntity> rows = buildFxRows(raw);
            if (!rows.isEmpty()) {
                exchangeRateDao.upsertAll(rows);
            }
            return rows.size();
        });
    }

    /**
     * Pure: groups the Frankfurter response by date, then for each date derives the six
     * non-identity directed pairs among USD/EUR/UAH. Dates missing either USD or UAH in
     * the response are silently skipped — that's a provider-side gap, not a bug here.
     */
    @NonNull
    static List<ExchangeRateEntity> buildFxRows(@NonNull List<FrankfurterRateDto> raw) {
        Map<String, Map<String, BigDecimal>> byDate = new LinkedHashMap<>();
        for (FrankfurterRateDto r : raw) {
            if (r == null || r.date == null || r.quote == null) continue;
            byDate.computeIfAbsent(r.date, d -> new HashMap<>())
                    .put(r.quote, BigDecimal.valueOf(r.rate));
        }

        List<ExchangeRateEntity> out = new ArrayList<>(byDate.size() * 6);
        for (Map.Entry<String, Map<String, BigDecimal>> e : byDate.entrySet()) {
            Map<String, BigDecimal> m = e.getValue();
            BigDecimal eurUsd = m.get("USD");
            BigDecimal eurUah = m.get("UAH");
            if (eurUsd == null || eurUah == null) continue;

            LocalDate date = LocalDate.parse(e.getKey());
            add(out, Currency.EUR, Currency.USD, date, eurUsd);
            add(out, Currency.EUR, Currency.UAH, date, eurUah);
            add(out, Currency.USD, Currency.EUR, date, BigDecimal.ONE.divide(eurUsd, FX_MC));
            add(out, Currency.UAH, Currency.EUR, date, BigDecimal.ONE.divide(eurUah, FX_MC));
            add(out, Currency.USD, Currency.UAH, date, eurUah.divide(eurUsd, FX_MC));
            add(out, Currency.UAH, Currency.USD, date, eurUsd.divide(eurUah, FX_MC));
        }
        return out;
    }

    private static void add(
            List<ExchangeRateEntity> out,
            Currency src,
            Currency tgt,
            LocalDate date,
            BigDecimal rate) {
        ExchangeRateEntity row = new ExchangeRateEntity();
        row.sourceCurrency = src;
        row.targetCurrency = tgt;
        row.date = date;
        row.rate = rate;
        out.add(row);
    }
}
