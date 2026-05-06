package com.my.finmon.notifications;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.my.finmon.data.model.EventType;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPayment;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

/**
 * Persistent dedup set for bond-payment notifications. Each payment fires at most
 * once per bucket ({@link Bucket#TODAY} or {@link Bucket#WEEK}). Lets the worker
 * broaden its 1-week-ahead window from "exactly 7 days out" to "in 1..7 days out"
 * so a missed worker run can catch up on the next day without duplicating an
 * already-sent ping.
 *
 * <p>Backed by a dedicated SharedPreferences file (separate from
 * {@code finmon_prefs} so it can be wiped without affecting user-facing settings).
 * Stored as a {@code Set<String>} of {@code "<bondId>|<isoDate>|<EventType>|<Bucket>"}
 * keys; cleanup drops keys older than 30 days so the set stays bounded.
 */
public final class BondNotificationDedup {

    public enum Bucket { TODAY, WEEK }

    private static final String FILE = "finmon_notif_dedup";
    private static final String KEY_SENT = "sent_keys";
    /** A pay-date this many days behind today is safe to forget — we'll never
     *  notify for past dates again. */
    private static final int RETENTION_DAYS = 30;

    private final SharedPreferences prefs;

    public BondNotificationDedup(@NonNull Context appContext) {
        this.prefs = appContext.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** True if a notification for this {@code (payment, bucket)} has already been sent. */
    public boolean isSent(@NonNull ExpectedPayment p, @NonNull Bucket bucket) {
        return readKeys().contains(keyOf(p.bondAssetId, p.date, p.type, bucket));
    }

    /** Records {@code (payment, bucket)} as sent. Subsequent {@link #isSent} calls
     *  return true until the key falls out of {@link #cleanup}'s retention window. */
    public void markSent(@NonNull ExpectedPayment p, @NonNull Bucket bucket) {
        Set<String> updated = new HashSet<>(readKeys());
        updated.add(keyOf(p.bondAssetId, p.date, p.type, bucket));
        writeKeys(updated);
    }

    /** Drops keys whose pay-date is more than {@link #RETENTION_DAYS} behind {@code today}.
     *  Cheap to call on every worker run — bounded set, in-memory walk. */
    public void cleanup(@NonNull LocalDate today) {
        Set<String> current = readKeys();
        if (current.isEmpty()) return;
        LocalDate cutoff = today.minusDays(RETENTION_DAYS);
        Set<String> kept = new HashSet<>(current.size());
        for (String key : current) {
            LocalDate d = parseDate(key);
            if (d == null || !d.isBefore(cutoff)) kept.add(key);
        }
        if (kept.size() != current.size()) writeKeys(kept);
    }

    @NonNull
    private Set<String> readKeys() {
        // SharedPreferences hands back a Set view that should be treated as read-only;
        // copying defensively before any mutation.
        Set<String> stored = prefs.getStringSet(KEY_SENT, null);
        return stored != null ? stored : new HashSet<>();
    }

    private void writeKeys(@NonNull Set<String> keys) {
        prefs.edit().putStringSet(KEY_SENT, keys).apply();
    }

    @NonNull
    private static String keyOf(
            long bondId,
            @NonNull LocalDate date,
            @NonNull EventType type,
            @NonNull Bucket bucket) {
        return bondId + "|" + date + "|" + type.name() + "|" + bucket.name();
    }

    /** Extracts the {@link LocalDate} component from a stored key. Returns null on
     *  any parse error so {@link #cleanup} keeps unrecognised entries (better to
     *  leak than to forget and re-notify). */
    private static LocalDate parseDate(@NonNull String key) {
        String[] parts = key.split("\\|");
        if (parts.length < 4) return null;
        try {
            return LocalDate.parse(parts[1]);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Test/utility hook — used in unit tests, not by production code. */
    void clearAllForTests() {
        prefs.edit().remove(KEY_SENT).apply();
    }
}
