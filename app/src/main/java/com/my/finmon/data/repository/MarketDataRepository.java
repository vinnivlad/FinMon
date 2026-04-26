package com.my.finmon.data.repository;

import androidx.annotation.NonNull;

import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.ExchangeRateEntity;
import com.my.finmon.data.entity.StockPriceEntity;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.remote.frankfurter.FrankfurterClient;
import com.my.finmon.data.remote.frankfurter.FrankfurterRateDto;
import com.my.finmon.data.remote.nbu.NbuBondDto;
import com.my.finmon.data.remote.nbu.NbuClient;
import com.my.finmon.data.remote.yahoo.YahooClient;
import com.my.finmon.data.remote.yahoo.YahooClient.DailyAndEvents;
import com.my.finmon.data.remote.yahoo.YahooClient.SearchHit;

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

    private final YahooClient yahooClient;
    private final FrankfurterClient frankfurterClient;
    private final NbuClient nbuClient;
    private final StockPriceDao stockPriceDao;
    private final ExchangeRateDao exchangeRateDao;
    private final ExecutorService executor;

    public MarketDataRepository(
            @NonNull YahooClient yahooClient,
            @NonNull FrankfurterClient frankfurterClient,
            @NonNull NbuClient nbuClient,
            @NonNull StockPriceDao stockPriceDao,
            @NonNull ExchangeRateDao exchangeRateDao,
            @NonNull ExecutorService executor) {
        this.yahooClient = yahooClient;
        this.frankfurterClient = frankfurterClient;
        this.nbuClient = nbuClient;
        this.stockPriceDao = stockPriceDao;
        this.exchangeRateDao = exchangeRateDao;
        this.executor = executor;
    }

    /**
     * Fetches daily closes from Yahoo for {@code remoteSymbol} (bare for US, exchange-suffixed
     * for non-US — e.g. {@code VOO}, {@code SXR8.DE}) in [{@code from}, {@code to}] inclusive
     * and upserts them into {@code stock_price}. Rows are keyed by {@code storageTicker} (the
     * domain symbol, e.g. {@code VOO}) so they line up with {@code asset.ticker}. Returns the
     * number of rows written.
     */
    @NonNull
    public Future<Integer> fetchAndStoreStockPrices(
            @NonNull String remoteSymbol,
            @NonNull String storageTicker,
            @NonNull LocalDate from,
            @NonNull LocalDate to) {
        return executor.submit(() -> {
            List<StockPriceEntity> rows = yahooClient.fetchDaily(remoteSymbol, storageTicker, from, to);
            if (!rows.isEmpty()) {
                stockPriceDao.upsertAll(rows);
            }
            return rows.size();
        });
    }

    /**
     * Like {@link #fetchAndStoreStockPrices} but also pulls dividend + split events from
     * the same Yahoo call. Prices are written here; events are returned to the caller —
     * which is {@link com.my.finmon.sync.PortfolioSyncWorker}, since event ingestion is
     * a domain operation that goes through {@code PortfolioRepository.ingestStockEvents}.
     *
     * <p>Caller is responsible for forwarding the returned events to the portfolio
     * repository for idempotent writes.
     */
    @NonNull
    public Future<DailyAndEvents> fetchAndStoreStockPricesWithEvents(
            @NonNull String remoteSymbol,
            @NonNull String storageTicker,
            @NonNull LocalDate from,
            @NonNull LocalDate to) {
        return executor.submit(() -> {
            DailyAndEvents result = yahooClient.fetchDailyAndEvents(remoteSymbol, storageTicker, from, to);
            if (!result.prices.isEmpty()) {
                stockPriceDao.upsertAll(result.prices);
            }
            return result;
        });
    }

    /**
     * Free-text symbol search. Yahoo's search results are pre-filtered to equities/ETFs
     * and surface as {@link SearchHit} rows. No DB writes.
     */
    @NonNull
    public Future<List<SearchHit>> searchSymbols(@NonNull String query) {
        return executor.submit(() -> yahooClient.searchSymbols(query));
    }

    /**
     * Looks up a security's reporting currency via Yahoo's chart meta. Yahoo search
     * results don't include currency, so we resolve it on selection. Returns the raw
     * ISO code (e.g. {@code "USD"}, {@code "EUR"}) — caller maps onto the app's
     * {@code Currency} enum and rejects unsupported values.
     */
    @NonNull
    public Future<String> lookupCurrency(@NonNull String remoteSymbol) {
        return executor.submit(() -> yahooClient.lookupCurrency(remoteSymbol));
    }

    /**
     * NBU bond search by ISIN / name / issuer substring. Hits a one-hour in-memory
     * cache after the first call so autocomplete keystrokes don't re-fetch the full
     * (~hundreds of bonds) listing.
     */
    @NonNull
    public Future<List<NbuBondDto>> searchBonds(@NonNull String query) {
        return executor.submit(() -> nbuClient.search(query));
    }

    /**
     * Looks up a single bond by ISIN. Used by the sync worker to find the payment
     * schedule of a held bond when ingesting past coupons.
     */
    @NonNull
    public Future<NbuBondDto> findBondByIsin(@NonNull String isin) {
        return executor.submit(() -> nbuClient.findByIsin(isin));
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
