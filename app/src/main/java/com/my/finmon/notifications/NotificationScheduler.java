package com.my.finmon.notifications;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.TimeUnit;

/**
 * Enqueues / cancels the two notification workers based on the master switch in
 * {@link com.my.finmon.prefs.UserPreferences}. Called from {@code FinMonApplication}
 * on every cold start (state-syncing) and from Settings when the user flips the
 * toggle.
 *
 * <p>Schedule:
 * <ul>
 *     <li>Bond payments — daily at ~12:00 local. Worker checks for bonds paying today
 *         and bonds paying in exactly 7 days.</li>
 *     <li>Weekly P&amp;L — every 7 days at ~Sunday 19:00 local.</li>
 * </ul>
 *
 * <p>WorkManager periodic constraints are inexact (~5–15 min jitter on idle). For
 * informational finance notifications that's well within tolerance.
 *
 * <p>{@code KEEP} policy on enqueue: if the work already exists, leave its current
 * fire schedule untouched — we don't want every cold start to push the next firing
 * forward by a day. Toggle off ({@link #cancel}) hard-cancels; the next toggle-on
 * enqueues fresh with a freshly computed initial delay.
 */
public final class NotificationScheduler {

    private static final String UNIQUE_BOND = "finmon_notif_bond";
    private static final String UNIQUE_PNL = "finmon_notif_pnl";

    private static final LocalTime BOND_FIRE_TIME = LocalTime.of(12, 0);
    private static final LocalTime PNL_FIRE_TIME = LocalTime.of(19, 0);
    private static final DayOfWeek PNL_FIRE_DAY = DayOfWeek.SUNDAY;

    private NotificationScheduler() {}

    /** Aligns scheduled work with the current preference state. */
    public static void apply(@NonNull Context ctx, boolean enabled) {
        if (enabled) enable(ctx);
        else cancel(ctx);
    }

    private static void enable(@NonNull Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        wm.enqueueUniquePeriodicWork(
                UNIQUE_BOND,
                ExistingPeriodicWorkPolicy.KEEP,
                buildBondRequest());
        wm.enqueueUniquePeriodicWork(
                UNIQUE_PNL,
                ExistingPeriodicWorkPolicy.KEEP,
                buildPnlRequest());
    }

    private static void cancel(@NonNull Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        wm.cancelUniqueWork(UNIQUE_BOND);
        wm.cancelUniqueWork(UNIQUE_PNL);
    }

    private static PeriodicWorkRequest buildBondRequest() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        return new PeriodicWorkRequest.Builder(
                BondPaymentNotificationWorker.class, 1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setInitialDelay(millisUntilNext(BOND_FIRE_TIME), TimeUnit.MILLISECONDS)
                .build();
    }

    private static PeriodicWorkRequest buildPnlRequest() {
        // No network required — weekly P&L reads local data only.
        return new PeriodicWorkRequest.Builder(
                WeeklyPnlNotificationWorker.class, 7, TimeUnit.DAYS)
                .setInitialDelay(
                        millisUntilNextWeekly(PNL_FIRE_DAY, PNL_FIRE_TIME),
                        TimeUnit.MILLISECONDS)
                .build();
    }

    /** Milliseconds until the next occurrence of {@code time} (today if still ahead,
     *  otherwise tomorrow). */
    private static long millisUntilNext(@NonNull LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime target = now.with(time);
        if (!target.isAfter(now)) target = target.plusDays(1);
        return Duration.between(now, target).toMillis();
    }

    /** Milliseconds until the next occurrence of {@code dayOfWeek} at {@code time}. */
    private static long millisUntilNextWeekly(@NonNull DayOfWeek dayOfWeek, @NonNull LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime target = now.with(TemporalAdjusters.nextOrSame(dayOfWeek)).with(time);
        if (!target.isAfter(now)) target = target.plusWeeks(1);
        return Duration.between(now, target).toMillis();
    }
}
