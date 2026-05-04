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
        viewModel.paymentsLabel().observe(getViewLifecycleOwner(), this::renderPaymentsLabel);
    }

    private void renderPaymentsLabel(
            @Nullable BondsViewModel.PaymentsLabel label) {
        if (binding == null || label == null) return;
        int res;
        switch (label) {
            case RECEIVED:
                res = R.string.bonds_received_payments_label;
                break;
            case RECEIVED_AND_EXPECTED:
                res = R.string.bonds_received_and_expected_payments_label;
                break;
            case EXPECTED:
            default:
                res = R.string.bonds_expected_payments_label;
                break;
        }
        binding.expectedPaymentsLabel.setText(res);
    }

    private void renderExpectedPayments(@Nullable ExpectedPaymentsResult r) {
        if (binding == null) return;
        boolean empty = (r == null || r.payments.isEmpty());

        // Headline split + equivalents + breakdown rows are all part of the
        // "non-empty" presentation; empty state replaces them with an italic line.
        int headlineVis = empty ? View.GONE : View.VISIBLE;
        binding.expectedPaymentsHeadlineInteger.setVisibility(headlineVis);
        binding.expectedPaymentsHeadlineFraction.setVisibility(headlineVis);
        binding.expectedPaymentsHeadlineCurrency.setVisibility(headlineVis);
        binding.expectedPaymentsEquivalents.setVisibility(headlineVis);
        binding.expectedPaymentsInnerRule.setVisibility(headlineVis);
        binding.expectedPaymentsCouponsRow.setVisibility(headlineVis);
        binding.expectedPaymentsMaturityRow.setVisibility(headlineVis);
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
        renderHeadlineSplit(headlineAmount, headlineCurrency);

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
        bindTypeRow(binding.expectedPaymentsCouponsRow,
                binding.expectedPaymentsCoupons, couponsByCurrency);
        bindTypeRow(binding.expectedPaymentsMaturityRow,
                binding.expectedPaymentsMaturity, maturityByCurrency);

        binding.expectedPaymentsFxGap.setVisibility(r.hasFxGaps ? View.VISIBLE : View.GONE);
    }

    /**
     * Editorial split-headline render: large serif integer, smaller serif decimal
     * fraction, Inter-caps currency code on the side. Mirrors Portfolio.
     */
    private void renderHeadlineSplit(@NonNull BigDecimal amount, @NonNull Currency ccy) {
        String formatted = MONEY.format(amount);
        int dot = formatted.lastIndexOf('.');
        String intPart;
        String fracPart;
        if (dot >= 0) {
            intPart = formatted.substring(0, dot);
            fracPart = formatted.substring(dot);
        } else {
            intPart = formatted;
            fracPart = "";
        }
        binding.expectedPaymentsHeadlineInteger.setText(intPart);
        binding.expectedPaymentsHeadlineFraction.setText(fracPart);
        binding.expectedPaymentsHeadlineCurrency.setText(ccy.name());
    }

    /**
     * Render one of the Coupons/Maturity rows. Hides the whole row (label + value)
     * when no amounts exist for that type so the section doesn't carry an empty
     * "Coupons" label with nothing next to it.
     */
    private void bindTypeRow(
            @NonNull View row,
            @NonNull android.widget.TextView amountsView,
            @NonNull Map<Currency, BigDecimal> byCurrency) {
        StringBuilder amounts = new StringBuilder();
        for (Currency c : Currency.values()) {
            BigDecimal v = byCurrency.get(c);
            if (v == null || v.signum() == 0) continue;
            if (amounts.length() > 0) amounts.append(" · ");
            amounts.append(MONEY.format(v)).append(' ').append(c.name());
        }
        if (amounts.length() == 0) {
            row.setVisibility(View.GONE);
        } else {
            amountsView.setText(amounts.toString());
            row.setVisibility(View.VISIBLE);
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
