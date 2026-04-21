package com.my.finmon.data.remote.stooq;

import androidx.annotation.NonNull;

import com.my.finmon.data.entity.StockPriceEntity;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches daily end-of-day prices from Stooq as CSV. One-time captcha-gated API key is
 * injected via constructor — see local.properties / BuildConfig.STOOQ_API_KEY.
 *
 * Synchronous by design: the caller is already on a background thread (ServiceLocator's
 * ioExecutor), so wrapping in Retrofit/OkHttp's async machinery adds no value for CSV.
 */
public final class StooqClient {

    private static final String HOST = "stooq.com";
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OkHttpClient http;
    private final String apiKey;

    public StooqClient(@NonNull OkHttpClient http, @NonNull String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    /**
     * Daily EOD prices for {@code ticker} in [{@code from}, {@code to}] inclusive.
     * Empty list if Stooq has no data for the range (unknown ticker, all non-trading
     * days, etc.). Throws on network error, non-2xx response, or rejected/missing key.
     */
    @NonNull
    public List<StockPriceEntity> fetchDaily(
            @NonNull String ticker,
            @NonNull LocalDate from,
            @NonNull LocalDate to) throws IOException {

        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host(HOST)
                .addPathSegments("q/d/l/")
                .addQueryParameter("s", ticker)
                .addQueryParameter("d1", from.format(YYYYMMDD))
                .addQueryParameter("d2", to.format(YYYYMMDD))
                .addQueryParameter("i", "d")
                .addQueryParameter("apikey", apiKey)
                .build();

        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Stooq HTTP " + resp.code() + " for ticker " + ticker);
            }
            ResponseBody body = resp.body();
            String csv = (body == null) ? "" : body.string();
            return StooqCsvParser.parse(csv, ticker);
        }
    }
}
