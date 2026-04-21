package com.my.finmon.data.remote.frankfurter;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import retrofit2.Response;

/**
 * Thin wrapper over {@link FrankfurterService} — pins {@code providers=NBU}, formats dates,
 * throws IOException on non-2xx, and returns the raw DTO list.
 *
 * Synchronous: the caller is already on {@code ioExecutor}, so there's no benefit to
 * Retrofit's enqueue/async path. Callers convert DTOs → ExchangeRateEntity rows themselves
 * (that logic belongs in MarketDataRepository, not here).
 */
public final class FrankfurterClient {

    /**
     * Only NBU publishes the UAH rate (it's the only Frankfurter v2 provider that does),
     * so we pin it here. If we later want USD/EUR without UAH, we can add a second method
     * with a different provider list.
     */
    private static final String PROVIDERS = "NBU";

    private final FrankfurterService service;

    public FrankfurterClient(@NonNull FrankfurterService service) {
        this.service = service;
    }

    @NonNull
    public List<FrankfurterRateDto> fetchLatest() throws IOException {
        return execute(service.getLatest(PROVIDERS));
    }

    @NonNull
    public List<FrankfurterRateDto> fetchRange(@NonNull LocalDate from, @NonNull LocalDate to)
            throws IOException {
        return execute(service.getRange(PROVIDERS, from.toString(), to.toString()));
    }

    private static <T> List<FrankfurterRateDto> execute(retrofit2.Call<List<FrankfurterRateDto>> call)
            throws IOException {
        Response<List<FrankfurterRateDto>> resp = call.execute();
        if (!resp.isSuccessful()) {
            throw new IOException("Frankfurter HTTP " + resp.code());
        }
        List<FrankfurterRateDto> body = resp.body();
        return body != null ? body : Collections.emptyList();
    }
}
