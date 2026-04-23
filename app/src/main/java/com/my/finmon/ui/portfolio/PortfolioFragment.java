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
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.databinding.FragmentPortfolioBinding;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
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

        viewModel.holdings().observe(getViewLifecycleOwner(), list -> {
            adapter.submitList(list);
            boolean empty = (list == null || list.isEmpty());
            binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        });

        viewModel.totals().observe(getViewLifecycleOwner(), this::bindTotals);

        binding.totalsCard.setOnClickListener(v ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_portfolio_to_breakdown));

        binding.fab.setOnClickListener(this::showFabMenu);
    }

    private void bindTotals(@Nullable PortfolioTotals t) {
        if (t == null) return;

        binding.totalAmount.setText(MONEY.format(t.valueInBase) + " " + t.baseCurrency.name());

        // Ribbon of the same total in other display currencies.
        StringBuilder others = new StringBuilder();
        for (Map.Entry<Currency, BigDecimal> e : t.valueByDisplayCurrency.entrySet()) {
            if (e.getKey() == t.baseCurrency) continue;
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
                MONEY.format(t.investedInBase) + " " + t.baseCurrency.name()));

        if (t.investedInBase.signum() != 0) {
            BigDecimal pct = t.pnlInBase.divide(t.investedInBase.abs(), PCT_MC).multiply(HUNDRED);
            binding.totalPnl.setText(
                    SIGNED_MONEY.format(t.pnlInBase) + " " + t.baseCurrency.name()
                            + " (" + PCT.format(pct) + ")");
        } else {
            binding.totalPnl.setText(SIGNED_MONEY.format(t.pnlInBase) + " " + t.baseCurrency.name());
        }
        int color = t.pnlInBase.signum() > 0
                ? R.color.pnl_positive
                : (t.pnlInBase.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.totalPnl.setTextColor(ContextCompat.getColor(requireContext(), color));

        binding.fxGapHint.setVisibility(t.hasFxGaps ? View.VISIBLE : View.GONE);
    }

    private void showFabMenu(@NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.portfolio_fab_menu);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_add_asset) {
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_portfolio_to_addAsset);
                return true;
            }
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
