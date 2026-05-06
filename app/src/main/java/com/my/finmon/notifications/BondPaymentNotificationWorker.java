package com.my.finmon.notifications;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.my.finmon.R;
import com.my.finmon.ServiceLocator;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPayment;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPaymentsResult;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Daily worker that posts up to two notifications:
 * <ul>
 *     <li><b>Today:</b> bonds paying coupon or maturity today.</li>
 *     <li><b>Upcoming:</b> bonds paying in the next 1..7 days.</li>
 * </ul>
 * Same-day payments are grouped into one notification per bucket. Per-payment
 * dedup ({@link BondNotificationDedup}) means each payment fires at most once per
 * bucket — so a missed worker run on day X catches up the next day without
 * double-pinging payments that already fired.
 *
 * <p>Scheduled by {@link NotificationScheduler}; checks the master switch on each
 * run and no-ops when disabled (covers the toggle-off → before-cancel race).
 */
public final class BondPaymentNotificationWorker extends Worker {

    private static final String TAG = "BondNotifWorker";

    /** Window the upcoming bucket scans, in days from today (inclusive both ends). */
    private static final int UPCOMING_LOOKAHEAD_DAYS = 7;

    private static final DecimalFormat WHOLE = buildFormat("#,##0");
    private static final DecimalFormat MONEY = buildFormat("#,##0.00");

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        return new DecimalFormat(pattern, sym);
    }

    public BondPaymentNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        ServiceLocator sl = ServiceLocator.get(ctx);
        if (!sl.userPreferences().isNotificationsEnabled()) {
            return Result.success();
        }
        try {
            LocalDate today = LocalDate.now();
            LocalDate windowEnd = today.plusDays(UPCOMING_LOOKAHEAD_DAYS);

            BondNotificationDedup dedup = new BondNotificationDedup(ctx);
            // Drop any keys whose pay-date is already well in the past — keeps the
            // SharedPreferences set small without ever forgetting recent fires.
            dedup.cleanup(today);

            PortfolioRepository repo = sl.portfolioRepository();
            ExpectedPaymentsResult result =
                    repo.getBondPaymentsInWindow(today, windowEnd, today, null).get();

            List<ExpectedPayment> dueToday = new ArrayList<>();
            List<ExpectedPayment> dueUpcoming = new ArrayList<>();
            for (ExpectedPayment p : result.payments) {
                if (p.date.equals(today)) {
                    // Today bucket: notify regardless of paid flag — by the time this
                    // worker fires at ~12:00, sync has typically already booked today's
                    // cash, so paid=true is the common case. Dedup gates duplicate
                    // fires within the day.
                    if (!dedup.isSent(p, BondNotificationDedup.Bucket.TODAY)) {
                        dueToday.add(p);
                    }
                } else if (p.date.isAfter(today) && !p.date.isAfter(windowEnd)) {
                    // Upcoming bucket: payment date in (today, today+7]. Broadened from
                    // a strict "==today+7" check so a worker that missed day X catches
                    // up on day X+1; dedup prevents re-notifying for the same payment
                    // on subsequent days while the date remains in the window.
                    if (!dedup.isSent(p, BondNotificationDedup.Bucket.WEEK)) {
                        dueUpcoming.add(p);
                    }
                }
            }

            if (!dueToday.isEmpty()) {
                NotificationHelper.post(
                        ctx,
                        NotificationHelper.ID_BOND_TODAY,
                        ctx.getString(R.string.notif_bond_today_title),
                        composeBody(ctx, dueToday));
                for (ExpectedPayment p : dueToday) {
                    dedup.markSent(p, BondNotificationDedup.Bucket.TODAY);
                }
            }
            if (!dueUpcoming.isEmpty()) {
                NotificationHelper.post(
                        ctx,
                        NotificationHelper.ID_BOND_UPCOMING,
                        ctx.getString(R.string.notif_bond_upcoming_title),
                        composeUpcomingBody(ctx, dueUpcoming, today));
                for (ExpectedPayment p : dueUpcoming) {
                    dedup.markSent(p, BondNotificationDedup.Bucket.WEEK);
                }
            }
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "bond notification check failed", e);
            return Result.retry();
        }
    }

    /** "VOO: 4,500.00 USD\nUA4...: 28,875 UAH" — one line per bond, monetary in
     *  native currency. For >3 bonds, collapse to a per-currency total summary
     *  to keep the notification body readable. Used for the today bucket — every
     *  payment shares "today" so the date is implicit in the title. */
    @NonNull
    private String composeBody(@NonNull Context ctx, @NonNull List<ExpectedPayment> payments) {
        if (payments.size() <= 3) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < payments.size(); i++) {
                if (i > 0) sb.append('\n');
                ExpectedPayment p = payments.get(i);
                sb.append(p.ticker).append(": ")
                        .append(MONEY.format(p.amount)).append(' ')
                        .append(p.currency.name());
            }
            return sb.toString();
        }
        // 4+ payments: aggregate by currency.
        Map<Currency, BigDecimal> totals = new EnumMap<>(Currency.class);
        for (ExpectedPayment p : payments) {
            totals.merge(p.currency, p.amount, BigDecimal::add);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(R.string.notif_bond_count, payments.size()));
        sb.append('\n');
        boolean first = true;
        for (Map.Entry<Currency, BigDecimal> e : totals.entrySet()) {
            if (!first) sb.append(" · ");
            first = false;
            sb.append(WHOLE.format(e.getValue())).append(' ').append(e.getKey().name());
        }
        return sb.toString();
    }

    /** Upcoming-bucket body — same shape as {@link #composeBody} but includes
     *  the days-until-payment per row since the lookahead can land anywhere in
     *  1..7 days. Aggregate fallback drops the per-row counter and just shows
     *  payment count + currency totals. */
    @NonNull
    private String composeUpcomingBody(
            @NonNull Context ctx,
            @NonNull List<ExpectedPayment> payments,
            @NonNull LocalDate today) {
        if (payments.size() <= 3) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < payments.size(); i++) {
                if (i > 0) sb.append('\n');
                ExpectedPayment p = payments.get(i);
                long days = java.time.temporal.ChronoUnit.DAYS.between(today, p.date);
                sb.append(p.ticker).append(' ')
                        .append(ctx.getString(R.string.notif_bond_in_days, days))
                        .append(": ")
                        .append(MONEY.format(p.amount)).append(' ')
                        .append(p.currency.name());
            }
            return sb.toString();
        }
        return composeBody(ctx, payments);
    }
}
