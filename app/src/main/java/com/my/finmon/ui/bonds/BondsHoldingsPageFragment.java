package com.my.finmon.ui.bonds;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.MaturedBond;
import com.my.finmon.data.repository.PortfolioRepository.WindowedHolding;
import com.my.finmon.databinding.FragmentBondsHoldingsBinding;
import com.my.finmon.ui.portfolio.HoldingsAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Active bonds list (windowed via the global filter) with the collapsible
 * matured-bonds section beneath. Reuses {@link HoldingsAdapter} so the row layout
 * matches Portfolio's stocks/bonds rendering exactly.
 */
public class BondsHoldingsPageFragment extends Fragment {

    private FragmentBondsHoldingsBinding binding;
    private BondsViewModel viewModel;
    private HoldingsAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentBondsHoldingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new HoldingsAdapter();
        binding.bondsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.bondsList.setAdapter(adapter);
        binding.bondsList.addItemDecoration(
                new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        viewModel = new ViewModelProvider(requireParentFragment()).get(BondsViewModel.class);

        adapter.setOnToggleMaturedListener(viewModel::toggleMaturedExpanded);
        adapter.setOnActiveClickListener(wh ->
                BondDetailDialog.show(this, wh.holding.asset.id));

        viewModel.activeBonds().observe(getViewLifecycleOwner(), list -> rebuild());
        viewModel.maturedBonds().observe(getViewLifecycleOwner(), list -> rebuild());
        viewModel.maturedExpanded().observe(getViewLifecycleOwner(), exp -> rebuild());
        viewModel.filterCurrency().observe(getViewLifecycleOwner(), c -> rebuild());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void rebuild() {
        if (binding == null) return;
        List<WindowedHolding> active = viewModel.activeBonds().getValue();
        List<MaturedBond> matured = viewModel.maturedBonds().getValue();
        Currency filter = viewModel.filterCurrency().getValue();
        boolean expanded = Boolean.TRUE.equals(viewModel.maturedExpanded().getValue());

        if (active == null) active = Collections.emptyList();
        if (matured == null) matured = Collections.emptyList();

        // Currency filter narrows the matured list client-side (the VM exposes them
        // unfiltered so the same fetch serves All and specific-currency modes).
        List<MaturedBond> visibleMatured = matured;
        if (filter != null) {
            visibleMatured = new ArrayList<>(matured.size());
            for (MaturedBond b : matured) {
                if (b.currency == filter) visibleMatured.add(b);
            }
        }

        List<HoldingsAdapter.Item> items = new ArrayList<>(
                active.size() + 1 + visibleMatured.size());
        for (WindowedHolding wh : active) items.add(new HoldingsAdapter.Item.Active(wh));
        if (!visibleMatured.isEmpty()) {
            items.add(new HoldingsAdapter.Item.MaturedHeader(visibleMatured.size(), expanded));
            if (expanded) {
                for (MaturedBond b : visibleMatured) items.add(new HoldingsAdapter.Item.Matured(b));
            }
        }
        adapter.submitList(items);

        boolean empty = active.isEmpty() && visibleMatured.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }
}
