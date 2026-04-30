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
import com.my.finmon.prefs.UserPreferences;
import com.my.finmon.ServiceLocator;
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
        boolean empty = (r == null || r.payments.isEmpty());

        binding.expectedPaymentsHeadline.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.expectedPaymentsEquivalents.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.expectedPaymentsCoupons.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.expectedPaymentsMaturity.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.expectedPaymentsFxGap.setVisibility(View.GONE);
        binding.expectedPaymentsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) return;

        // Headline: total in user's display currency. Mirrors Portfolio's totals card.
        UserPreferences prefs = ServiceLocator.get(requireContext()).userPreferences();
        Currency display = prefs.getDisplayCurrency();
        BigDecimal headlineAmount = r.totalsByDisplayCurrency.get(display);
        Currency headlineCurrency = display;
        if (headlineAmount == null) {
            // Display currency wasn't computable (FX gap) — fall back to repo's
            // immutable BASE_CURRENCY (USD) total.
            headlineAmount = r.totalInBase;
            headlineCurrency = r.baseCurrency;
        }
        binding.expectedPaymentsHeadline.setText(
                MONEY.format(headlineAmount) + " " + headlineCurrency.name());

        // Equivalents ribbon: same total expressed in the *other* display currencies.
        StringBuilder others = new StringBuilder();
        for (Currency c : Currency.values()) {
            if (c == headlineCurrency) continue;
            BigDecimal v = r.totalsByDisplayCurrency.get(c);
            if (v == null || v.signum() == 0) continue;
            if (others.length() > 0) others.append(" · ");
            others.append(MONEY.format(v)).append(' ').append(c.name());
        }
        if (others.length() > 0) {
            binding.expectedPaymentsEquivalents.setText("≈ " + others);
            binding.expectedPaymentsEquivalents.setVisibility(View.VISIBLE);
        } else {
            binding.expectedPaymentsEquivalents.setVisibility(View.GONE);
        }

        // Type breakdown — sum amounts per type, per native currency, so the user can
        // tell how much of the flow is coupon income vs principal redemption AND in
        // which currency it'll arrive.
        java.util.EnumMap<Currency, BigDecimal> couponsByCurrency =
                new java.util.EnumMap<>(Currency.class);
        java.util.EnumMap<Currency, BigDecimal> maturityByCurrency =
                new java.util.EnumMap<>(Currency.class);
        for (ExpectedPayment p : r.payments) {
            (p.type == EventType.MATURITY ? maturityByCurrency : couponsByCurrency)
                    .merge(p.currency, p.amount, BigDecimal::add);
        }
        bindTypeLine(binding.expectedPaymentsCoupons,
                couponsByCurrency, R.string.bonds_expected_type_coupons);
        bindTypeLine(binding.expectedPaymentsMaturity,
                maturityByCurrency, R.string.bonds_expected_type_maturity);

        binding.expectedPaymentsFxGap.setVisibility(r.hasFxGaps ? View.VISIBLE : View.GONE);
    }

    private void bindTypeLine(
            @NonNull android.widget.TextView view,
            @NonNull Map<Currency, BigDecimal> byCurrency,
            int labelRes) {
        StringBuilder amounts = new StringBuilder();
        for (Currency c : Currency.values()) {
            BigDecimal v = byCurrency.get(c);
            if (v == null || v.signum() == 0) continue;
            if (amounts.length() > 0) amounts.append(", ");
            amounts.append(MONEY.format(v)).append(' ').append(c.name());
        }
        if (amounts.length() == 0) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(getString(labelRes, amounts.toString()));
            view.setVisibility(View.VISIBLE);
        }
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
