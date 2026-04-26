package com.my.finmon.data.remote.yahoo;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Retrofit interface for Yahoo Finance's v8 chart endpoint. Unauthenticated, but Yahoo
 * rejects requests with an empty/missing User-Agent — a default UA is set on the OkHttp
 * client in {@link com.my.finmon.ServiceLocator}.
 *
 * <p>Symbol convention: bare for US-listed (e.g. {@code VOO}), exchange suffix for non-US
 * (e.g. {@code SXR8.DE}). Note this differs from Stooq's lowercase convention.
 *
 * <p>The chart endpoint is unofficial and has historically required a cookie+crumb dance
 * for some flows. The bare /v8/chart call does not (as of 2026); if Yahoo changes that,
 * we'll see HTTP 401 and add the dance then.
 */
public interface YahooService {

    /**
     * Daily candles for {@code symbol} over an explicit epoch-second window.
     * Set {@code events} to {@code "div|split"} (URL-encoded) when phase 2 needs them.
     */
    @GET("v8/finance/chart/{symbol}")
    Call<YahooChartResponse> getChart(
            @Path("symbol") String symbol,
            @Query("interval") String interval,
            @Query("period1") long period1Epoch,
            @Query("period2") long period2Epoch,
            @Query("events") String events);

    /**
     * Fuzzy ticker / company-name search. Used by the trade-form autocomplete to surface
     * Yahoo-known instruments the user hasn't yet added locally.
     */
    @GET("v1/finance/search")
    Call<YahooSearchResponse> search(@Query("q") String q);

    /**
     * Recent close + meta only, scoped to a single day. Used after a user picks a
     * search result to confirm the security's reporting currency.
     */
    @GET("v8/finance/chart/{symbol}")
    Call<YahooChartResponse> getChartShort(
            @Path("symbol") String symbol,
            @Query("interval") String interval,
            @Query("range") String range);
}
