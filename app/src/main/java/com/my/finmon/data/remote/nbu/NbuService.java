package com.my.finmon.data.remote.nbu;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * Retrofit interface for NBU's open-data endpoints. Today only the depository
 * securities listing — every UA bond currently tradable in Ukraine, with full
 * payment schedule per bond.
 *
 * <p>Note: the {@code ?json} switch is required to get JSON; default content type
 * is HTML/XML. We pass it as a {@link Query} with no value so Retrofit emits
 * {@code ?json} rather than {@code ?json=true}.
 */
public interface NbuService {

    /**
     * All bonds currently registered with the NBU depositary. Matured bonds drop
     * out of this list — that's an inherent limitation we work around via manual
     * entry / import for older holdings.
     */
    @GET("depo_securities?json")
    Call<List<NbuBondDto>> getDepoSecurities();
}
