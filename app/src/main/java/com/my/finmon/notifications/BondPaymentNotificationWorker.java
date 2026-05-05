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
 *     <li><b>1 week ahead:</b> bonds paying exactly 7 days from today.</li>
 * </ul>
 * Same-day payments are grouped into one notification per "today" / "1 week" bucket.
 * Scheduled by {@link NotificationScheduler}; checks the master switch on each run
 * and no-ops when disabled (covers the toggle-off → before-cancel race).
 */
public final class BondPaymentNotificationWorker extends Worker {

    private static final String TAG = "BondNotifWorker";

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
            LocalDate weekFromNow = today.plusDays(7);

            // One repo call covers both buckets — pull all payments in [today, today+7].
            PortfolioRepository repo = sl.portfolioRepository();
            ExpectedPaymentsResult result =
                    repo.getBondPaymentsInWindow(today, weekFromNow, today, null).get();

            List<ExpectedPayment> dueToday = new ArrayList<>();
            List<ExpectedPayment> dueInOneWeek = new ArrayList<>();
            for (ExpectedPayment p : result.payments) {
                if (p.paid) continue;  // already booked from the event log
                if (p.date.equals(today)) dueToday.add(p);
                else if (p.date.equals(weekFromNow)) dueInOneWeek.add(p);
            }

            if (!dueToday.isEmpty()) {
                NotificationHelper.post(
                        ctx,
                        NotificationHelper.ID_BOND_TODAY,
                        ctx.getString(R.string.notif_bond_today_title),
                        composeBody(ctx, dueToday));
            }
            if (!dueInOneWeek.isEmpty()) {
                NotificationHelper.post(
                        ctx,
                        NotificationHelper.ID_BOND_UPCOMING,
                        ctx.getString(R.string.notif_bond_upcoming_title),
                        composeBody(ctx, dueInOneWeek));
            }
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "bond notification check failed", e);
            return Result.retry();
        }
    }

    /** "VOO: 4,500.00 USD\nUA4...: 28,875 UAH" — one line per bond, monetary in
     *  native currency. For >3 bonds, collapse to a per-currency total summary
     *  to keep the notification body readable. */
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
}
