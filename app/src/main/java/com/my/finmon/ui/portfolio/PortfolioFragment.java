package com.my.finmon.ui.portfolio;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListPopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.databinding.FragmentPortfolioBinding;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

/**
 * Hosts the Portfolio screen's two-tab layout: Holdings (page 0) + Analytics (page 1).
 * The totals card and FAB stay here as siblings of the ViewPager2; the pages
 * themselves are slim observers of this fragment's {@link PortfolioViewModel}.
 */
public class PortfolioFragment extends Fragment {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final DecimalFormat PCT = buildFormat("+0.0'%';-0.0'%'");
    private static final MathContext PCT_MC = new MathContext(4, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private FragmentPortfolioBinding binding;
    private PortfolioViewModel viewModel;
    private TabLayoutMediator tabMediator;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPortfolioBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(
                this,
                PortfolioViewModel.factory(requireContext())
        ).get(PortfolioViewModel.class);

        binding.portfolioPager.setAdapter(new PortfolioPagerAdapter(this));
        tabMediator = new TabLayoutMediator(
                binding.portfolioTabs, binding.portfolioPager,
                (TabLayout.Tab tab, int position) -> {
                    int titleRes = (position == PortfolioPagerAdapter.PAGE_ANALYTICS)
                            ? R.string.portfolio_tab_analytics
                            : R.string.portfolio_tab_holdings;
                    tab.setText(titleRes);
                });
        tabMediator.attach();

        viewModel.totals().observe(getViewLifecycleOwner(),
                t -> bindTotals(t, viewModel.displayCurrency().getValue()));
        viewModel.displayCurrency().observe(getViewLifecycleOwner(),
                c -> bindTotals(viewModel.totals().getValue(), c));

        binding.totalsCard.setOnClickListener(v ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_portfolio_to_breakdown));

        binding.fab.setOnClickListener(this::showFabMenu);
    }

    private void bindTotals(@Nullable PortfolioTotals t, @Nullable Currency displayCurrency) {
        if (t == null) return;

        Currency primary = displayCurrency != null ? displayCurrency : t.baseCurrency;
        BigDecimal primaryValue = t.valueByDisplayCurrency.get(primary);
        BigDecimal primaryInvested = t.investedByDisplayCurrency.get(primary);
        BigDecimal primaryPnl = t.pnlByDisplayCurrency.get(primary);
        if (primaryValue == null || primaryInvested == null || primaryPnl == null) {
            primary = t.baseCurrency;
            primaryValue = t.valueInBase;
            primaryInvested = t.investedInBase;
            primaryPnl = t.pnlInBase;
        }

        binding.totalAmount.setText(MONEY.format(primaryValue) + " " + primary.name());

        StringBuilder others = new StringBuilder();
        for (Map.Entry<Currency, BigDecimal> e : t.valueByDisplayCurrency.entrySet()) {
            if (e.getKey() == primary) continue;
            if (others.length() > 0) others.append(" · ");
            others.append(MONEY.format(e.getValue())).append(' ').append(e.getKey().name());
        }
        if (others.length() > 0) {
            binding.totalDisplayEquivalents.setText("≈ " + others);
            binding.totalDisplayEquivalents.setVisibility(View.VISIBLE);
        } else {
            binding.totalDisplayEquivalents.setVisibility(View.GONE);
        }

        binding.totalInvested.setText(getString(
                R.string.totals_invested_label,
                MONEY.format(primaryInvested) + " " + primary.name()));

        if (primaryInvested.signum() != 0) {
            BigDecimal pct = primaryPnl.divide(primaryInvested.abs(), PCT_MC).multiply(HUNDRED);
            binding.totalPnl.setText(
                    SIGNED_MONEY.format(primaryPnl) + " " + primary.name()
                            + " (" + PCT.format(pct) + ")");
        } else {
            binding.totalPnl.setText(SIGNED_MONEY.format(primaryPnl) + " " + primary.name());
        }
        int color = primaryPnl.signum() > 0
                ? R.color.pnl_positive
                : (primaryPnl.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.totalPnl.setTextColor(ContextCompat.getColor(requireContext(), color));

        binding.fxGapHint.setVisibility(t.hasFxGaps ? View.VISIBLE : View.GONE);
    }

    private void showFabMenu(@NonNull View anchor) {
        // PopupMenu auto-picks placement and prefers below the anchor; using ListPopupWindow
        // so we can pin the menu above the FAB (negative vertical offset = anchor height +
        // measured menu height, so the menu's bottom sits just above the FAB's top).
        String[] labels = { getString(R.string.menu_record_trade) };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), R.layout.item_fab_menu, labels);

        ListPopupWindow popup = new ListPopupWindow(requireContext());
        popup.setAnchorView(anchor);
        popup.setAdapter(adapter);
        popup.setModal(true);

        // Measure one row to compute the upward offset. WRAP_CONTENT for height keeps the
        // popup tight to its content; the offset just needs to clear the FAB.
        View row = adapter.getView(0, null, (ViewGroup) anchor.getParent());
        row.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int rowH = row.getMeasuredHeight();
        int rowW = row.getMeasuredWidth();
        popup.setContentWidth(rowW);
        popup.setVerticalOffset(-(anchor.getHeight() + rowH * labels.length));

        popup.setOnItemClickListener((parent, v, position, id) -> {
            popup.dismiss();
            if (position == 0) {
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_portfolio_to_addTrade);
            }
        });
        popup.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Repository isn't reactive — manual refresh covers the "came back from add-trade" case.
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

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }
}
