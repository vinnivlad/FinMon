package com.my.finmon.ui.portfolio;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
import com.my.finmon.data.repository.PortfolioRepository.WindowedHolding;
import com.my.finmon.databinding.FragmentPortfolioBinding;
import com.my.finmon.ui.filter.GlobalFilterViewModel;
import com.my.finmon.ui.portfolio.PortfolioViewModel.RibbonEntry;
import com.my.finmon.ui.portfolio.PortfolioViewModel.TotalsCardData;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Portfolio screen — totals card on top, holdings list below, FAB for actions.
 *
 * <p>The totals card is fed by a single {@link TotalsCardData} LiveData; the
 * fragment just renders pre-shaped fields (headline / invested / Period P&amp;L /
 * ribbon entries / fx-gap flag). All filter-aware decisions happen in the VM, so
 * the card never re-renders mid-load and never shifts content as data trickles in.
 */
public class PortfolioFragment extends Fragment {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final DecimalFormat PCT = buildFormat("+0.0'%';-0.0'%'");

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
        viewModel.totalsCard().observe(getViewLifecycleOwner(), this::renderTotalsCard);

        // Record trade is the only action behind the FAB at the moment, so skip the
        // single-item popup and route the tap straight to the form.
        binding.fab.setOnClickListener(v -> NavHostFragment.findNavController(this)
                .navigate(R.id.action_portfolio_to_addTrade));
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

    private void renderTotalsCard(@Nullable TotalsCardData d) {
        if (binding == null || d == null) return;

        String ccy = d.currency.name();
        binding.totalAmount.setText(MONEY.format(d.valueEnd) + " " + ccy);

        if (d.ribbon.isEmpty()) {
            binding.totalDisplayEquivalents.setVisibility(View.GONE);
        } else {
            StringBuilder others = new StringBuilder();
            for (RibbonEntry r : d.ribbon) {
                if (others.length() > 0) others.append(" · ");
                others.append(MONEY.format(r.amount)).append(' ').append(r.currency.name());
            }
            binding.totalDisplayEquivalents.setText("≈ " + others);
            binding.totalDisplayEquivalents.setVisibility(View.VISIBLE);
        }

        binding.totalInvested.setText(getString(
                R.string.totals_invested_label,
                MONEY.format(d.investedEnd) + " " + ccy));

        StringBuilder pnlText = new StringBuilder();
        pnlText.append(getString(R.string.chart_period_pnl_label))
                .append(": ")
                .append(SIGNED_MONEY.format(d.periodPnl))
                .append(' ')
                .append(ccy);
        if (d.periodPnlPct != null) {
            pnlText.append(" (").append(PCT.format(d.periodPnlPct)).append(')');
        }
        binding.totalPnl.setText(pnlText.toString());
        int color = d.periodPnl.signum() > 0
                ? R.color.pnl_positive
                : (d.periodPnl.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.totalPnl.setTextColor(ContextCompat.getColor(requireContext(), color));

        binding.fxGapHint.setVisibility(d.hasFxGaps ? View.VISIBLE : View.GONE);
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
