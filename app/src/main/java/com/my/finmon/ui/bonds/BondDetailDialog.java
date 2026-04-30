package com.my.finmon.ui.bonds;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.my.finmon.R;
import com.my.finmon.ServiceLocator;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.EventType;
import com.my.finmon.data.repository.PortfolioRepository.BondTimeline;
import com.my.finmon.data.repository.PortfolioRepository.BondTimelineEntry;
import com.my.finmon.databinding.DialogBondDetailBinding;
import com.my.finmon.databinding.ItemBondCouponLineBinding;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Async-loading detail popup for a bond row. Fetches the {@link BondTimeline} on
 * the view executor, then shows a {@link MaterialAlertDialogBuilder}-backed dialog
 * with yield / maturity at the top and a list of past (greyed) + future coupons
 * below.
 */
final class BondDetailDialog {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }

    private BondDetailDialog() {}

    static void show(@NonNull Fragment host, long bondAssetId) {
        ServiceLocator sl = ServiceLocator.get(host.requireContext());
        sl.viewExecutor().execute(() -> {
            BondTimeline tl;
            try {
                tl = sl.portfolioRepository()
                        .getBondTimeline(bondAssetId, LocalDate.now()).get();
            } catch (Exception ignored) {
                return;
            }
            if (!host.isAdded() || host.getActivity() == null) return;
            host.requireActivity().runOnUiThread(() -> render(host, tl));
        });
    }

    private static void render(@NonNull Fragment host, @NonNull BondTimeline tl) {
        if (!host.isAdded()) return;
        AssetEntity bond = tl.bond;
        if (bond == null) return;

        LayoutInflater inflater = LayoutInflater.from(host.requireContext());
        DialogBondDetailBinding binding = DialogBondDetailBinding.inflate(inflater);

        binding.bondYield.setText(bond.bondYieldPct != null
                ? host.getString(R.string.bond_detail_yield, formatPct(bond.bondYieldPct))
                : host.getString(R.string.bond_detail_yield_unknown));
        binding.bondMaturity.setText(bond.bondMaturityDate != null
                ? host.getString(R.string.bond_detail_maturity, bond.bondMaturityDate.toString())
                : host.getString(R.string.bond_detail_maturity_unknown));

        // Paid (past entries) vs expected (future entries) sums in the bond's
        // native currency. Both run from the same timeline list — partition by
        // the paid flag so a single pass populates both.
        BigDecimal paidSum = BigDecimal.ZERO;
        BigDecimal expectedSum = BigDecimal.ZERO;
        for (BondTimelineEntry e : tl.entries) {
            if (e.paid) paidSum = paidSum.add(e.amount);
            else expectedSum = expectedSum.add(e.amount);
        }
        String ccy = bond.currency.name();
        binding.bondPaidSum.setText(host.getString(
                R.string.bond_detail_paid_sum, MONEY.format(paidSum) + " " + ccy));
        binding.bondExpectedSum.setText(host.getString(
                R.string.bond_detail_expected_sum, MONEY.format(expectedSum) + " " + ccy));

        if (tl.entries.isEmpty()) {
            binding.bondCouponList.setVisibility(View.GONE);
            binding.bondCouponEmpty.setVisibility(View.VISIBLE);
        } else {
            binding.bondCouponList.setVisibility(View.VISIBLE);
            binding.bondCouponEmpty.setVisibility(View.GONE);
            for (BondTimelineEntry e : tl.entries) {
                addCouponLine(host, binding.bondCouponList, e, bond.currency.name());
            }
        }

        String title = bond.name != null && !bond.name.isBlank()
                ? bond.ticker + " · " + bond.name
                : bond.ticker;

        new MaterialAlertDialogBuilder(host.requireContext())
                .setTitle(title)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.bond_detail_close, null)
                .show();
    }

    private static void addCouponLine(
            @NonNull Fragment host,
            @NonNull LinearLayout container,
            @NonNull BondTimelineEntry entry,
            @NonNull String ccyLabel) {
        ItemBondCouponLineBinding row = ItemBondCouponLineBinding.inflate(
                LayoutInflater.from(container.getContext()), container, false);
        row.couponDate.setText(entry.date.toString());
        row.couponType.setText(host.getString(entry.type == EventType.MATURITY
                ? R.string.bond_detail_type_maturity
                : R.string.bond_detail_type_coupon));
        row.couponAmount.setText(MONEY.format(entry.amount) + " " + ccyLabel);

        // Past entries are dimmed so the user can tell at a glance which payments
        // already landed vs. which are still upcoming.
        float alpha = entry.paid ? 0.45f : 1.0f;
        applyAlpha(row.couponDate, alpha);
        applyAlpha(row.couponType, alpha);
        applyAlpha(row.couponAmount, alpha);

        container.addView(row.getRoot());
    }

    private static void applyAlpha(@NonNull TextView v, float alpha) {
        v.setAlpha(alpha);
    }

    private static String formatPct(@NonNull BigDecimal pct) {
        // Strip trailing zeros so "11.6700" reads as "11.67". stripTrailingZeros can
        // return scientific notation for whole numbers, so cap with toPlainString.
        return pct.stripTrailingZeros().toPlainString();
    }
}
