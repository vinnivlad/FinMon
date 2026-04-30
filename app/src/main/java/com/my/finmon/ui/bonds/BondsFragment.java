package com.my.finmon.ui.bonds;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.model.EventType;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPayment;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPaymentsResult;
import com.my.finmon.databinding.FragmentBondsBinding;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

/**
 * Hosts the Bonds screen — Expected Payments card on top, then a TabLayout of
 * Holdings (active bonds + matured-bonds collapsible) and Calendar (payment-date
 * grid). The Activity-scoped {@link GlobalFilterViewModel} drives both pages: the
 * currency filter narrows them, the period filter scopes Holdings' windowed P&amp;L.
 */
public class BondsFragment extends Fragment {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }

    private FragmentBondsBinding binding;
    private BondsViewModel viewModel;
    private TabLayoutMediator tabMediator;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentBondsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        GlobalFilterViewModel filter = new ViewModelProvider(
                requireActivity(), GlobalFilterViewModel.factory(requireContext()))
                .get(GlobalFilterViewModel.class);
        viewModel = new ViewModelProvider(this, BondsViewModel.factory(requireContext(), filter))
                .get(BondsViewModel.class);

        binding.bondsPager.setAdapter(new BondsPagerAdapter(this));
        tabMediator = new TabLayoutMediator(
                binding.bondsTabs, binding.bondsPager,
                (TabLayout.Tab tab, int position) -> {
                    int titleRes = (position == BondsPagerAdapter.PAGE_CALENDAR)
                            ? R.string.bonds_tab_calendar
                            : R.string.bonds_tab_holdings;
                    tab.setText(titleRes);
                });
        tabMediator.attach();

        viewModel.expectedPayments().observe(getViewLifecycleOwner(), this::renderExpectedPayments);
    }

    private void renderExpectedPayments(@Nullable ExpectedPaymentsResult r) {
        if (binding == null) return;
        if (r == null || r.payments.isEmpty()) {
            binding.expectedPaymentsBody.setText(R.string.bonds_expected_payments_none);
            return;
        }

        StringBuilder sb = new StringBuilder();
        // Per-currency rows in declaration order (USD, EUR, UAH) for stable layout.
        for (Currency c : Currency.values()) {
            BigDecimal total = r.totalsByCurrency.get(c);
            if (total == null || total.signum() == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.name()).append(": ").append(MONEY.format(total));
        }
        // Base-currency total only adds value when the user holds payments in more
        // than one currency — collapse the redundant duplicate line otherwise.
        boolean multiCurrency = countNonZero(r.totalsByCurrency) > 1;
        if (multiCurrency) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("≈ ").append(MONEY.format(r.totalInBase))
                    .append(' ').append(r.baseCurrency.name());
            if (r.hasFxGaps) {
                sb.append(' ').append(getString(R.string.bonds_expected_payments_fx_gap));
            }
        }
        // Type breakdown — sum amounts per type, per currency. So the user can tell
        // how much of the upcoming flow is coupon income vs principal redemption.
        java.util.EnumMap<Currency, BigDecimal> couponsByCurrency =
                new java.util.EnumMap<>(Currency.class);
        java.util.EnumMap<Currency, BigDecimal> maturityByCurrency =
                new java.util.EnumMap<>(Currency.class);
        for (ExpectedPayment p : r.payments) {
            (p.type == EventType.MATURITY ? maturityByCurrency : couponsByCurrency)
                    .merge(p.currency, p.amount, BigDecimal::add);
        }
        appendTypeLine(sb, couponsByCurrency, R.string.bonds_expected_type_coupons);
        appendTypeLine(sb, maturityByCurrency, R.string.bonds_expected_type_maturity);

        binding.expectedPaymentsBody.setText(sb.toString());
    }

    private void appendTypeLine(
            @NonNull StringBuilder sb,
            @NonNull Map<Currency, BigDecimal> byCurrency,
            int labelRes) {
        StringBuilder amounts = new StringBuilder();
        for (Currency c : Currency.values()) {
            BigDecimal v = byCurrency.get(c);
            if (v == null || v.signum() == 0) continue;
            if (amounts.length() > 0) amounts.append(", ");
            amounts.append(MONEY.format(v)).append(' ').append(c.name());
        }
        if (amounts.length() == 0) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(getString(labelRes, amounts.toString()));
    }

    private static int countNonZero(@NonNull Map<Currency, BigDecimal> map) {
        int n = 0;
        for (BigDecimal v : map.values()) {
            if (v != null && v.signum() != 0) n++;
        }
        return n;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.refresh();
    }

    @Override
    public void onDestroyView() {
        if (tabMediator != null) {
            tabMediator.detach();
            tabMediator = null;
        }
        super.onDestroyView();
        binding = null;
    }
}
