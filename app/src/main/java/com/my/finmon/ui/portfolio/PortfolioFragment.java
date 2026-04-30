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
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.my.finmon.R;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.data.repository.PortfolioRepository.WindowedHolding;
import com.my.finmon.databinding.FragmentPortfolioBinding;
import com.my.finmon.ui.filter.GlobalFilterViewModel;
import com.my.finmon.ui.portfolio.PortfolioViewModel.PeriodTotals;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Portfolio screen — totals card on top, holdings list below, FAB for actions.
 * Allocation pies and the per-currency breakdown that used to live as inner tabs
 * here have moved to the Charts screen + Breakdown tab respectively, so this
 * screen is single-purpose now: "what do I currently hold?".
 *
 * <p>The totals card consumes the global filter — headline value/invested and
 * Period P&amp;L come from {@link PortfolioViewModel#periodTotals} (snapshot-based,
 * agrees with Charts → Value totals card for the same filter). The cross-currency
 * ribbon below the headline only shows in "All" currency mode.
 */
public class PortfolioFragment extends Fragment {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final DecimalFormat PCT = buildFormat("+0.0'%';-0.0'%'");
    private static final MathContext PCT_MC = new MathContext(4, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private FragmentPortfolioBinding binding;
    private PortfolioViewModel viewModel;
    private HoldingsAdapter holdingsAdapter;

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

        GlobalFilterViewModel globalFilter = new ViewModelProvider(
                requireActivity(), GlobalFilterViewModel.factory(requireContext()))
                .get(GlobalFilterViewModel.class);
        viewModel = new ViewModelProvider(
                this, PortfolioViewModel.factory(requireContext(), globalFilter))
                .get(PortfolioViewModel.class);

        holdingsAdapter = new HoldingsAdapter();
        binding.holdingsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.holdingsList.setAdapter(holdingsAdapter);
        binding.holdingsList.addItemDecoration(
                new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        viewModel.windowedHoldings().observe(getViewLifecycleOwner(), this::renderHoldings);
        // Totals card: periodTotals drives headline + Period P&L; lifetime totals
        // feed the cross-currency ribbon; filter currency decides whether ribbon shows.
        viewModel.periodTotals().observe(getViewLifecycleOwner(), pt -> rebindTotalsCard());
        viewModel.totals().observe(getViewLifecycleOwner(), t -> rebindTotalsCard());
        viewModel.filterCurrency().observe(getViewLifecycleOwner(), c -> rebindTotalsCard());

        binding.fab.setOnClickListener(this::showFabMenu);
    }

    private void renderHoldings(@Nullable List<WindowedHolding> active) {
        if (binding == null) return;
        if (active == null) active = Collections.emptyList();

        // Cash piles are summarised in the cashBar above the list, so they don't
        // belong in the asset rows. Sort STOCK → BOND → (CASH never reaches the
        // adapter but keeps the comparator total). AssetType ordinal happens to
        // match that order, so a plain ordinal compare does the job.
        List<WindowedHolding> sorted = new ArrayList<>(active);
        sorted.sort((a, b) -> Integer.compare(
                a.holding.asset.type.ordinal(), b.holding.asset.type.ordinal()));

        List<HoldingsAdapter.Item> items = new ArrayList<>(sorted.size());
        for (WindowedHolding wh : sorted) {
            if (wh.holding.asset.type == AssetType.CASH) continue;
            items.add(new HoldingsAdapter.Item.Active(wh));
        }
        holdingsAdapter.submitList(items);

        renderCashBar(active);

        // Empty-state triggers off the *full* list — if the user has only cash and
        // no STOCK/BOND, we want them to see the cash bar rather than the "no
        // holdings yet" placeholder.
        binding.emptyState.setVisibility(active.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void renderCashBar(@NonNull List<WindowedHolding> all) {
        if (binding == null) return;
        // Iterate in Currency declaration order (USD, EUR, UAH) for stable layout.
        StringBuilder sb = new StringBuilder();
        for (Currency c : Currency.values()) {
            WindowedHolding cashHolding = null;
            for (WindowedHolding wh : all) {
                if (wh.holding.asset.type == AssetType.CASH && wh.holding.asset.currency == c) {
                    cashHolding = wh;
                    break;
                }
            }
            if (cashHolding == null) continue;
            BigDecimal balance = cashHolding.holding.quantity;
            if (balance.signum() == 0) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            // Suffix is whatever name the cash asset carries — set via the import
            // JSON's "name" field (e.g. "€" for CASH_EUR). Falls back to the
            // currency code when the asset has no name yet.
            String suffix = cashHolding.holding.asset.name;
            if (suffix == null || suffix.isBlank()) suffix = c.name();
            sb.append(MONEY.format(balance)).append(' ').append(suffix);
        }
        if (sb.length() == 0) {
            binding.cashBar.setVisibility(View.GONE);
        } else {
            binding.cashBar.setText(sb.toString());
            binding.cashBar.setVisibility(View.VISIBLE);
        }
    }

    private void rebindTotalsCard() {
        if (binding == null) return;
        PeriodTotals pt = viewModel.periodTotals().getValue();
        PortfolioTotals lifetime = viewModel.totals().getValue();
        Currency filterCurrency = viewModel.filterCurrency().getValue();
        if (pt == null) return;

        Currency primary = pt.currency;
        binding.totalAmount.setText(MONEY.format(pt.valueEnd) + " " + primary.name());

        // Cross-currency ribbon only makes sense in All mode (filterCurrency == null);
        // in specific mode we're already drilled into one bucket, so other currencies
        // would just be noise.
        if (filterCurrency == null && lifetime != null) {
            StringBuilder others = new StringBuilder();
            for (Map.Entry<Currency, BigDecimal> e : lifetime.valueByDisplayCurrency.entrySet()) {
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
        } else {
            binding.totalDisplayEquivalents.setVisibility(View.GONE);
        }

        binding.totalInvested.setText(getString(
                R.string.totals_invested_label,
                MONEY.format(pt.investedEnd) + " " + primary.name()));

        StringBuilder pnlText = new StringBuilder();
        pnlText.append(getString(R.string.chart_period_pnl_label))
                .append(": ")
                .append(SIGNED_MONEY.format(pt.periodPnl))
                .append(' ')
                .append(primary.name());
        if (pt.periodPnlPct != null) {
            pnlText.append(" (").append(PCT.format(pt.periodPnlPct)).append(')');
        }
        binding.totalPnl.setText(pnlText.toString());
        int color = pt.periodPnl.signum() > 0
                ? R.color.pnl_positive
                : (pt.periodPnl.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.totalPnl.setTextColor(ContextCompat.getColor(requireContext(), color));

        boolean showFxHint = filterCurrency == null && lifetime != null && lifetime.hasFxGaps;
        binding.fxGapHint.setVisibility(showFxHint ? View.VISIBLE : View.GONE);
    }

    private void showFabMenu(@NonNull View anchor) {
        String[] labels = { getString(R.string.menu_record_trade) };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), R.layout.item_fab_menu, labels);

        ListPopupWindow popup = new ListPopupWindow(requireContext());
        popup.setAnchorView(anchor);
        popup.setAdapter(adapter);
        popup.setModal(true);

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
