package com.my.finmon.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.my.finmon.MainActivity;
import com.my.finmon.R;

/**
 * Centralized notification posting + channel management. All FinMon notifications
 * (bond payments, weekly P&L) flow through one channel so the user has a single
 * system-level on/off independent of the in-app master toggle.
 *
 * <p>Tap intent on every notification opens MainActivity. We don't deep-link
 * to a specific destination — the bond and P&L notifications are summary-shaped
 * (multiple bonds in one notification, period totals) and there's no obvious
 * single screen they correspond to.
 */
public final class NotificationHelper {

    /** Single channel for all FinMon-emitted notifications. */
    public static final String CHANNEL_ID = "finmon_alerts";

    /** Stable IDs so re-posting on the next periodic firing replaces the previous
     *  notification rather than stacking duplicates. */
    public static final int ID_BOND_TODAY = 1001;
    public static final int ID_BOND_UPCOMING = 1002;
    public static final int ID_WEEKLY_PNL = 1003;

    private NotificationHelper() {}

    /**
     * Idempotently registers the channel. Safe to call on every cold start —
     * Android dedups by ID. Importance DEFAULT (no sound on idle, no full-screen
     * intrusion) — these are informational, not urgent.
     */
    public static void ensureChannel(@NonNull Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(ctx.getString(R.string.notif_channel_description));
        nm.createNotificationChannel(ch);
    }

    /** Posts (or replaces, if same id) a notification. No-op when the user has
     *  denied POST_NOTIFICATIONS — NotificationManagerCompat silently swallows. */
    public static void post(
            @NonNull Context ctx,
            int id,
            @NonNull String title,
            @NonNull String body) {
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setColor(ContextCompat.getColor(ctx, R.color.fm_accent))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManagerCompat.from(ctx).notify(id, b.build());
    }
}
