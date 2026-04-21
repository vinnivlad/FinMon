package com.my.finmon.data.remote.frankfurter;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * Retrofit interface for the Frankfurter v2 /rates endpoint.
 *
 * Use {@code providers=NBU} so UAH, USD, and EUR all land in one EUR-based response
 * (v1 and the default v2 providers exclude UAH — see project_open_questions.md).
 * Responses are always arrays of {@link FrankfurterRateDto}, flat regardless of range.
 */
public interface FrankfurterService {

    /** Latest available rates (today in UTC if the provider has published). */
    @GET("v2/rates")
    Call<List<FrankfurterRateDto>> getLatest(@Query("providers") String providers);

    /** Rates for every date in [{@code from}, {@code to}] inclusive, both {@code yyyy-MM-dd}. */
    @GET("v2/rates")
    Call<List<FrankfurterRateDto>> getRange(
            @Query("providers") String providers,
            @Query("from") String from,
            @Query("to") String to);
}
