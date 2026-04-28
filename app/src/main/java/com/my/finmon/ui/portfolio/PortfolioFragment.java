package com.my.finmon.ui.portfolio;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.Holding;
import com.my.finmon.data.repository.PortfolioRepository.MaturedBond;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.databinding.FragmentPortfolioBinding;

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

public class PortfolioFragment extends Fragment {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final DecimalFormat PCT = buildFormat("+0.0'%';-0.0'%'");
    private static final MathContext PCT_MC = new MathContext(4, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private FragmentPortfolioBinding binding;
    private PortfolioViewModel viewModel;
    private HoldingsAdapter adapter;

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

        adapter = new HoldingsAdapter();
        binding.holdingsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.holdingsList.setAdapter(adapter);
        binding.holdingsList.addItemDecoration(
                new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        viewModel = new ViewModelProvider(
                this,
                PortfolioViewModel.factory(requireContext())
        ).get(PortfolioViewModel.class);

        adapter.setOnToggleMaturedListener(viewModel::toggleMaturedExpanded);

        // The adapter renders a single combined list. Recompute it whenever any of the
        // three inputs (active holdings, matured bonds, expanded flag) changes.
        viewModel.holdings().observe(getViewLifecycleOwner(), list -> rebuildAdapterList());
        viewModel.maturedBonds().observe(getViewLifecycleOwner(), list -> rebuildAdapterList());
        viewModel.maturedExpanded().observe(getViewLifecycleOwner(), exp -> rebuildAdapterList());

        viewModel.totals().observe(getViewLifecycleOwner(), t -> bindTotals(t, viewModel.displayCurrency().getValue()));
        viewModel.displayCurrency().observe(getViewLifecycleOwner(), c -> bindTotals(viewModel.totals().getValue(), c));

        binding.totalsCard.setOnClickListener(v ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_portfolio_to_breakdown));

        binding.fab.setOnClickListener(this::showFabMenu);
    }

    private void rebuildAdapterList() {
        if (binding == null) return;
        List<Holding> active = viewModel.holdings().getValue();
        List<MaturedBond> matured = viewModel.maturedBonds().getValue();
        boolean expanded = Boolean.TRUE.equals(viewModel.maturedExpanded().getValue());

        if (active == null) active = Collections.emptyList();
        if (matured == null) matured = Collections.emptyList();

        List<HoldingsAdapter.Item> items = new ArrayList<>(
                active.size() + 1 + matured.size());
        for (Holding h : active) items.add(new HoldingsAdapter.Item.Active(h));
        if (!matured.isEmpty()) {
            items.add(new HoldingsAdapter.Item.MaturedHeader(matured.size(), expanded));
            if (expanded) {
                for (MaturedBond b : matured) items.add(new HoldingsAdapter.Item.Matured(b));
            }
        }
        adapter.submitList(items);

        // Empty-state hint covers the no-active-and-no-matured case only — once a bond is
        // matured the user has portfolio history worth showing, so keep the section visible.
        boolean empty = active.isEmpty() && matured.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void bindTotals(@Nullable PortfolioTotals t, @Nullable Currency displayCurrency) {
        if (t == null) return;

        // Pick the user-chosen display currency, falling back to the base if no FX rate
        // exists (mirrors the gap-hint contract).
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

        // Ribbon of the same total in the other currencies.
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
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.portfolio_fab_menu);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_record_trade) {
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_portfolio_to_addTrade);
                return true;
            }
            return false;
        });
        menu.show();
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
