package com.my.finmon.notifications;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.my.finmon.R;
import com.my.finmon.ServiceLocator;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Weekly worker that posts a single notification with the portfolio's market P&L
 * delta over the past 7 days, in base currency (USD).
 *
 * <p>P&L is the period identity: {@code (value − invested)} now minus the same
 * 7 days ago. Capital deposits during the week add equally to {@code value} and
 * {@code invested}, so they cancel — the number reflects market movement only,
 * matching the project's "isolate market P&L from cash flows" core.
 */
public final class WeeklyPnlNotificationWorker extends Worker {

    private static final String TAG = "WeeklyPnlNotifWorker";

    private static final DecimalFormat SIGNED_WHOLE = buildFormat("+#,##0;-#,##0");
    private static final DecimalFormat SIGNED_PCT = buildFormat("+0.0'%';-0.0'%'");

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        return new DecimalFormat(pattern, sym);
    }

    public WeeklyPnlNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
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
            PortfolioRepository repo = sl.portfolioRepository();
            LocalDate today = LocalDate.now();
            LocalDate weekAgo = today.minusDays(7);

            PortfolioTotals nowT = repo.getPortfolioTotals(today).get();
            PortfolioTotals thenT = repo.getPortfolioTotals(weekAgo).get();

            BigDecimal pnlNow = nowT.valueInBase.subtract(nowT.investedInBase);
            BigDecimal pnlThen = thenT.valueInBase.subtract(thenT.investedInBase);
            BigDecimal periodPnl = pnlNow.subtract(pnlThen);

            String arrow = periodPnl.signum() > 0 ? "▲"
                    : periodPnl.signum() < 0 ? "▼" : "·";
            StringBuilder body = new StringBuilder();
            body.append(arrow).append(' ')
                    .append(SIGNED_WHOLE.format(periodPnl)).append(' ')
                    .append(nowT.baseCurrency.name());
            BigDecimal denom = thenT.valueInBase.abs();
            if (denom.signum() > 0) {
                BigDecimal pct = periodPnl.multiply(new BigDecimal("100"))
                        .divide(denom, java.math.MathContext.DECIMAL64);
                body.append(" (").append(SIGNED_PCT.format(pct)).append(')');
            }

            NotificationHelper.post(
                    ctx,
                    NotificationHelper.ID_WEEKLY_PNL,
                    ctx.getString(R.string.notif_weekly_pnl_title),
                    body.toString());
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "weekly P&L notification failed", e);
            return Result.retry();
        }
    }
}
