package com.my.finmon.data.remote.nbu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Response;

/**
 * Wrapper over {@link NbuService} with an in-memory cache. NBU's full bond list is
 * a few hundred entries (<1MB) — fetching once per app session and filtering locally
 * makes autocomplete keystrokes responsive without re-hitting the network.
 *
 * <p>Cache TTL is one hour; the periodic sync worker can also force a refresh.
 * Synchronous API: callers are already on {@code ioExecutor} so there's no value in
 * Retrofit's async path.
 */
public final class NbuClient {

    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;
    private static final int SEARCH_RESULT_CAP = 10;

    private final NbuService service;

    private volatile List<NbuBondDto> cached = null;
    private volatile long cachedAt = 0L;

    public NbuClient(@NonNull NbuService service) {
        this.service = service;
    }

    /**
     * Returns the entire bond list, hitting the network only when the cache is
     * stale (or absent). Call sites that just need to look something up should
     * prefer {@link #findByIsin}.
     */
    @NonNull
    public synchronized List<NbuBondDto> fetchAllCached() throws IOException {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAt) < CACHE_TTL_MS) return cached;
        return refresh();
    }

    /** Force-refresh the cache. Used by the sync worker to keep schedules current. */
    @NonNull
    public synchronized List<NbuBondDto> refresh() throws IOException {
        Response<List<NbuBondDto>> resp = service.getDepoSecurities().execute();
        if (!resp.isSuccessful()) {
            throw new IOException("NBU HTTP " + resp.code());
        }
        List<NbuBondDto> body = resp.body();
        cached = (body != null) ? body : Collections.emptyList();
        cachedAt = System.currentTimeMillis();
        return cached;
    }

    /**
     * Substring search against {@code cpcode} (ISIN) and {@code cpdescr}/{@code emit_name},
     * case-insensitive. Capped at {@value SEARCH_RESULT_CAP} hits — the list is huge and
     * an unfiltered match would flood the autocomplete dropdown.
     */
    @NonNull
    public List<NbuBondDto> search(@NonNull String query) throws IOException {
        String q = query.trim();
        if (q.isEmpty()) return Collections.emptyList();
        String upperQ = q.toUpperCase();

        List<NbuBondDto> all = fetchAllCached();
        List<NbuBondDto> hits = new ArrayList<>();
        for (NbuBondDto b : all) {
            if (matches(b, upperQ)) {
                hits.add(b);
                if (hits.size() >= SEARCH_RESULT_CAP) break;
            }
        }
        return hits;
    }

    @Nullable
    public NbuBondDto findByIsin(@NonNull String isin) throws IOException {
        for (NbuBondDto b : fetchAllCached()) {
            if (isin.equalsIgnoreCase(b.cpcode)) return b;
        }
        return null;
    }

    private static boolean matches(@NonNull NbuBondDto b, @NonNull String upperQuery) {
        if (b.cpcode != null && b.cpcode.toUpperCase().contains(upperQuery)) return true;
        if (b.cpdescr != null && b.cpdescr.toUpperCase().contains(upperQuery)) return true;
        if (b.emit_name != null && b.emit_name.toUpperCase().contains(upperQuery)) return true;
        return false;
    }
}
