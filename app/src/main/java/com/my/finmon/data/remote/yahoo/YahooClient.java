package com.my.finmon.data.remote.yahoo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.finmon.data.entity.StockPriceEntity;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import retrofit2.Response;

/**
 * Thin wrapper over {@link YahooService} that returns Room-ready rows.
 *
 * <p>Synchronous: callers are already on {@code ioExecutor}, so no benefit to Retrofit's
 * enqueue/async path.
 *
 * <p>{@link #fetchDaily} returns prices only. {@link #fetchDailyAndEvents} adds
 * {@code events=div|split} to the same call so the caller gets prices, dividends, and
 * splits in one round-trip.
 */
public final class YahooClient {

    private static final String INTERVAL_DAILY = "1d";
    /** Window padding so date-boundary effects don't drop the last bar. */
    private static final long PERIOD2_PADDING_SECONDS = 24 * 60 * 60L;

    private final YahooService service;

    public YahooClient(@NonNull YahooService service) {
        this.service = service;
    }

    /**
     * Daily closes for {@code remoteSymbol} (bare for US, suffixed for non-US — e.g.
     * {@code VOO}, {@code SXR8.DE}) in [{@code from}, {@code to}] inclusive. Returned
     * rows carry {@code storageTicker} (the domain symbol) so they key against
     * {@code asset.ticker} — Yahoo's symbol may include the exchange suffix that we
     * don't want to store on the price row.
     *
     * <p>Empty list if Yahoo returns no candles or an error. Throws on network failure
     * or non-2xx HTTP.
     */
    @NonNull
    public List<StockPriceEntity> fetchDaily(
            @NonNull String remoteSymbol,
            @NonNull String storageTicker,
            @NonNull LocalDate from,
            @NonNull LocalDate to) throws IOException {
        return fetchInternal(remoteSymbol, storageTicker, from, to, /* events */ null).prices;
    }

    /**
     * One-call fetch of prices + dividend events + split events for {@code remoteSymbol}.
     * Used by the sync worker so it can backfill prices and auto-create dividend/split
     * EventEntities from a single HTTP round-trip.
     */
    @NonNull
    public DailyAndEvents fetchDailyAndEvents(
            @NonNull String remoteSymbol,
            @NonNull String storageTicker,
            @NonNull LocalDate from,
            @NonNull LocalDate to) throws IOException {
        return fetchInternal(remoteSymbol, storageTicker, from, to, "div|split");
    }

    /**
     * Free-text search of Yahoo's symbol catalogue. Filters the response to equity-like
     * instruments (EQUITY, ETF) — currencies, indices, futures and options are dropped.
     */
    @NonNull
    public List<SearchHit> searchSymbols(@NonNull String query) throws IOException {
        Response<YahooSearchResponse> resp = service.search(query).execute();
        if (!resp.isSuccessful()) {
            throw new IOException("Yahoo search HTTP " + resp.code());
        }
        YahooSearchResponse body = resp.body();
        if (body == null || body.quotes == null) return Collections.emptyList();

        List<SearchHit> out = new ArrayList<>(body.quotes.size());
        for (YahooSearchResponse.Quote q : body.quotes) {
            if (q == null || q.symbol == null) continue;
            if (!"EQUITY".equals(q.quoteType) && !"ETF".equals(q.quoteType)) continue;
            String label = (q.longname != null && !q.longname.isEmpty()) ? q.longname : q.shortname;
            String exchange = (q.exchDisp != null && !q.exchDisp.isEmpty()) ? q.exchDisp : q.exchange;
            out.add(new SearchHit(q.symbol, label, exchange, q.quoteType));
        }
        return out;
    }

    /**
     * Time-series fetch for the Market browser tab — non-storing, returns timestamped
     * close prices and the meta needed to render the price-detail header (longName,
     * currency).
     *
     * <p>{@code interval} accepts Yahoo's standard tokens (1m / 5m / 15m / 30m / 60m /
     * 1d / 1wk / 1mo). {@code range} is also a Yahoo token (1d / 5d / 1mo / 6mo / 1y /
     * 5y / max / ytd). For arbitrary date windows use {@link #fetchSeriesWindow}
     * instead.
     */
    @NonNull
    public MarketSeries fetchSeriesByRange(
            @NonNull String remoteSymbol,
            @NonNull String interval,
            @NonNull String range) throws IOException {
        Response<YahooChartResponse> resp = service.getChartShort(remoteSymbol, interval, range).execute();
        if (!resp.isSuccessful()) {
            throw new IOException("Yahoo HTTP " + resp.code() + " for " + remoteSymbol);
        }
        return toSeries(remoteSymbol, firstResult(resp.body()));
    }

    /**
     * Time-series fetch over an explicit epoch-second window. Used for the Custom
     * range option in the Market browser.
     */
    @NonNull
    public MarketSeries fetchSeriesWindow(
            @NonNull String remoteSymbol,
            @NonNull String interval,
            long period1Epoch,
            long period2Epoch) throws IOException {
        Response<YahooChartResponse> resp = service
                .getChart(remoteSymbol, interval, period1Epoch, period2Epoch, /* events */ null)
                .execute();
        if (!resp.isSuccessful()) {
            throw new IOException("Yahoo HTTP " + resp.code() + " for " + remoteSymbol);
        }
        return toSeries(remoteSymbol, firstResult(resp.body()));
    }

    @NonNull
    private static MarketSeries toSeries(
            @NonNull String remoteSymbol,
            @Nullable YahooChartResponse.Result r) {
        if (r == null
                || r.timestamp == null
                || r.indicators == null
                || r.indicators.quote == null
                || r.indicators.quote.isEmpty()) {
            return MarketSeries.empty(remoteSymbol);
        }
        List<Double> closes = r.indicators.quote.get(0).close;
        if (closes == null) return MarketSeries.empty(remoteSymbol);

        int n = Math.min(r.timestamp.size(), closes.size());
        List<SeriesPoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Long ts = r.timestamp.get(i);
            Double close = closes.get(i);
            if (ts == null || close == null) continue;  // Yahoo null-pads non-trading bars
            points.add(new SeriesPoint(ts, BigDecimal.valueOf(close)));
        }

        String name = null;
        String currency = null;
        if (r.meta != null) {
            name = (r.meta.longName != null && !r.meta.longName.isEmpty())
                    ? r.meta.longName
                    : r.meta.shortName;
            currency = r.meta.currency;
        }
        return new MarketSeries(remoteSymbol, name, currency, points);
    }

    /**
     * One-shot meta lookup — used after a user picks a search result to discover the
     * security's reporting currency (Yahoo search itself doesn't include it). Returns
     * the ISO code as a string; the caller maps it onto the {@code Currency} enum and
     * rejects unsupported values.
     */
    @Nullable
    public String lookupCurrency(@NonNull String remoteSymbol) throws IOException {
        Response<YahooChartResponse> resp = service.getChartShort(remoteSymbol, "1d", "5d").execute();
        if (!resp.isSuccessful()) {
            throw new IOException("Yahoo chart HTTP " + resp.code() + " for " + remoteSymbol);
        }
        YahooChartResponse.Result r = firstResult(resp.body());
        if (r == null || r.meta == null) return null;
        return r.meta.currency;
    }

    @NonNull
    private DailyAndEvents fetchInternal(
            @NonNull String remoteSymbol,
            @NonNull String storageTicker,
            @NonNull LocalDate from,
            @NonNull LocalDate to,
            @Nullable String events) throws IOException {

        long p1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        // Pad by one day so the bar for {@code to} isn't truncated by Yahoo's exclusive end.
        long p2 = to.atStartOfDay(ZoneOffset.UTC).toEpochSecond() + PERIOD2_PADDING_SECONDS;

        Response<YahooChartResponse> resp = service
                .getChart(remoteSymbol, INTERVAL_DAILY, p1, p2, events)
                .execute();
        if (!resp.isSuccessful()) {
            throw new IOException("Yahoo HTTP " + resp.code() + " for " + remoteSymbol);
        }

        YahooChartResponse body = resp.body();
        YahooChartResponse.Result r = firstResult(body);
        if (r == null) return DailyAndEvents.empty();

        List<StockPriceEntity> prices = toPriceRows(r, storageTicker, from, to);
        List<DividendEvent> dividends = toDividendEvents(r, from, to);
        List<SplitEvent> splits = toSplitEvents(r, from, to);
        return new DailyAndEvents(prices, dividends, splits);
    }

    @Nullable
    private static YahooChartResponse.Result firstResult(@Nullable YahooChartResponse body) {
        if (body == null || body.chart == null) return null;
        if (body.chart.error != null) return null;
        if (body.chart.result == null || body.chart.result.isEmpty()) return null;
        return body.chart.result.get(0);
    }

    @NonNull
    private static List<DividendEvent> toDividendEvents(
            @NonNull YahooChartResponse.Result r,
            @NonNull LocalDate from,
            @NonNull LocalDate to) {
        if (r.events == null || r.events.dividends == null) return Collections.emptyList();
        List<DividendEvent> out = new ArrayList<>();
        for (Map.Entry<String, YahooChartResponse.Dividend> e : r.events.dividends.entrySet()) {
            YahooChartResponse.Dividend d = e.getValue();
            if (d == null || d.amount == null || d.date == null) continue;
            LocalDateTime at = Instant.ofEpochSecond(d.date).atZone(ZoneOffset.UTC).toLocalDateTime();
            LocalDate day = at.toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            out.add(new DividendEvent(at, BigDecimal.valueOf(d.amount)));
        }
        return out;
    }

    @NonNull
    private static List<SplitEvent> toSplitEvents(
            @NonNull YahooChartResponse.Result r,
            @NonNull LocalDate from,
            @NonNull LocalDate to) {
        if (r.events == null || r.events.splits == null) return Collections.emptyList();
        List<SplitEvent> out = new ArrayList<>();
        for (Map.Entry<String, YahooChartResponse.Split> e : r.events.splits.entrySet()) {
            YahooChartResponse.Split s = e.getValue();
            if (s == null || s.date == null || s.numerator == null || s.denominator == null) continue;
            if (s.denominator == 0.0) continue;
            LocalDateTime at = Instant.ofEpochSecond(s.date).atZone(ZoneOffset.UTC).toLocalDateTime();
            LocalDate day = at.toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) continue;
            BigDecimal ratio = BigDecimal.valueOf(s.numerator)
                    .divide(BigDecimal.valueOf(s.denominator), java.math.MathContext.DECIMAL64);
            out.add(new SplitEvent(at, ratio));
        }
        return out;
    }

    @NonNull
    private static List<StockPriceEntity> toPriceRows(
            @NonNull YahooChartResponse.Result r,
            @NonNull String storageTicker,
            @NonNull LocalDate from,
            @NonNull LocalDate to) {
        if (r.timestamp == null
                || r.indicators == null
                || r.indicators.quote == null
                || r.indicators.quote.isEmpty()) {
            return Collections.emptyList();
        }
        List<Double> closes = r.indicators.quote.get(0).close;
        if (closes == null) return Collections.emptyList();

        int n = Math.min(r.timestamp.size(), closes.size());
        List<StockPriceEntity> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Long ts = r.timestamp.get(i);
            Double close = closes.get(i);
            if (ts == null || close == null) continue;  // Yahoo can null-pad gaps

            LocalDate d = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate();
            // Window padding can push us slightly past {@code to}; clamp.
            if (d.isBefore(from) || d.isAfter(to)) continue;

            StockPriceEntity row = new StockPriceEntity();
            row.ticker = storageTicker;
            row.date = d;
            row.closePrice = BigDecimal.valueOf(close);
            out.add(row);
        }
        return out;
    }

    /** Combined fetch result: prices + dividend + split events from a single Yahoo call. */
    public static final class DailyAndEvents {
        @NonNull public final List<StockPriceEntity> prices;
        @NonNull public final List<DividendEvent> dividends;
        @NonNull public final List<SplitEvent> splits;

        public DailyAndEvents(
                @NonNull List<StockPriceEntity> prices,
                @NonNull List<DividendEvent> dividends,
                @NonNull List<SplitEvent> splits) {
            this.prices = prices;
            this.dividends = dividends;
            this.splits = splits;
        }

        @NonNull
        static DailyAndEvents empty() {
            return new DailyAndEvents(
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
    }

    public static final class DividendEvent {
        @NonNull public final LocalDateTime at;
        @NonNull public final BigDecimal perShareAmount;

        public DividendEvent(@NonNull LocalDateTime at, @NonNull BigDecimal perShareAmount) {
            this.at = at;
            this.perShareAmount = perShareAmount;
        }
    }

    public static final class SplitEvent {
        @NonNull public final LocalDateTime at;
        /** numerator / denominator — e.g. 4 for a 4-for-1 forward split. */
        @NonNull public final BigDecimal ratio;

        public SplitEvent(@NonNull LocalDateTime at, @NonNull BigDecimal ratio) {
            this.at = at;
            this.ratio = ratio;
        }
    }

    /**
     * Time-series result for the Market browser tab. {@code points} are
     * timestamp-keyed (epoch seconds) so intraday bars survive intact.
     */
    public static final class MarketSeries {
        @NonNull public final String symbol;
        @Nullable public final String name;
        @Nullable public final String currency;
        @NonNull public final List<SeriesPoint> points;

        public MarketSeries(
                @NonNull String symbol,
                @Nullable String name,
                @Nullable String currency,
                @NonNull List<SeriesPoint> points) {
            this.symbol = symbol;
            this.name = name;
            this.currency = currency;
            this.points = points;
        }

        @NonNull
        static MarketSeries empty(@NonNull String symbol) {
            return new MarketSeries(symbol, null, null, Collections.emptyList());
        }
    }

    public static final class SeriesPoint {
        /** Epoch second (UTC). */
        public final long epochSecond;
        @NonNull public final BigDecimal close;

        public SeriesPoint(long epochSecond, @NonNull BigDecimal close) {
            this.epochSecond = epochSecond;
            this.close = close;
        }
    }

    /**
     * A single result from {@link #searchSymbols}. Currency isn't included — Yahoo
     * search doesn't always carry it; resolve via {@link #lookupCurrency} when the
     * user actually picks the result.
     */
    public static final class SearchHit {
        /** Yahoo symbol, exchange-suffixed for non-US (e.g. {@code SXR8.DE}). */
        @NonNull public final String symbol;
        /** {@code longname} when available, else {@code shortname}. May be empty. */
        @Nullable public final String name;
        /** Display exchange label, e.g. "NYSEArca", "XETRA". */
        @Nullable public final String exchange;
        /** Yahoo's {@code quoteType} — {@code EQUITY} or {@code ETF} after filtering. */
        @NonNull public final String quoteType;

        public SearchHit(
                @NonNull String symbol,
                @Nullable String name,
                @Nullable String exchange,
                @NonNull String quoteType) {
            this.symbol = symbol;
            this.name = name;
            this.exchange = exchange;
            this.quoteType = quoteType;
        }
    }
}
