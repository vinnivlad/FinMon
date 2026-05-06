package com.my.finmon.ui.bonds;

import android.view.LayoutInflater;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.model.EventType;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPayment;
import com.my.finmon.databinding.DialogDatePaymentsBinding;
import com.my.finmon.databinding.ItemBondCouponLineBinding;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dialog opened by tapping a payment-marker date on the Bonds → Calendar tab.
 * Lists every payment expected on that date (grouped per bond + type — lots of the
 * same bond are already combined upstream by {@code getBondPaymentsInWindow}) plus a
 * per-currency total at the bottom.
 */
final class DatePaymentsDialog {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    /** Locale-aware long date with weekday (e.g. "Wednesday, 7 February 2024"). */
    private static final DateTimeFormatter TITLE_FMT =
            DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)
                    .withLocale(Locale.getDefault());

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }

    private DatePaymentsDialog() {}

    static void show(
            @NonNull Fragment host,
            @NonNull LocalDate date,
            @NonNull List<ExpectedPayment> paymentsOnDate) {
        if (paymentsOnDate.isEmpty()) return;

        DialogDatePaymentsBinding binding = DialogDatePaymentsBinding.inflate(
                LayoutInflater.from(host.requireContext()));

        // Stable order: ticker, then type (Coupon before Principal — alphabetic).
        List<ExpectedPayment> ordered = new ArrayList<>(paymentsOnDate);
        ordered.sort((a, b) -> {
            int c = a.ticker.compareTo(b.ticker);
            if (c != 0) return c;
            return a.type.name().compareTo(b.type.name());
        });

        Map<Currency, BigDecimal> totalsByCurrency = new EnumMap<>(Currency.class);
        for (ExpectedPayment p : ordered) {
            addRow(host, binding.datePaymentsList, p);
            totalsByCurrency.merge(p.currency, p.amount, BigDecimal::add);
        }

        binding.datePaymentsTotal.setText(formatTotal(totalsByCurrency));

        new MaterialAlertDialogBuilder(host.requireContext())
                .setTitle(date.format(TITLE_FMT))
                .setView(binding.getRoot())
                .setPositiveButton(R.string.bond_detail_close, null)
                .show();
    }

    private static void addRow(
            @NonNull Fragment host,
            @NonNull LinearLayout container,
            @NonNull ExpectedPayment p) {
        ItemBondCouponLineBinding row = ItemBondCouponLineBinding.inflate(
                LayoutInflater.from(container.getContext()), container, false);
        row.couponDate.setText(p.ticker);
        row.couponType.setText(host.getString(p.type == EventType.MATURITY
                ? R.string.bond_detail_type_maturity
                : R.string.bond_detail_type_coupon));
        row.couponAmount.setText(MONEY.format(p.amount) + " " + p.currency.name());
        // Paid rows dim to 0.45 alpha — same treatment BondDetailDialog uses for
        // already-landed payments, so the calendar dialog reads consistently.
        float alpha = p.paid ? 0.45f : 1.0f;
        row.couponDate.setAlpha(alpha);
        row.couponType.setAlpha(alpha);
        row.couponAmount.setAlpha(alpha);
        container.addView(row.getRoot());
    }

    private static String formatTotal(@NonNull Map<Currency, BigDecimal> totals) {
        // Build "USD 100.00 · EUR 50.00" — mirrors the Expected Payments card style.
        StringBuilder sb = new StringBuilder();
        for (Currency c : Currency.values()) {
            BigDecimal v = totals.get(c);
            if (v == null || v.signum() == 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(MONEY.format(v)).append(' ').append(c.name());
        }
        return "Total: " + (sb.length() > 0 ? sb.toString() : "—");
    }
}
