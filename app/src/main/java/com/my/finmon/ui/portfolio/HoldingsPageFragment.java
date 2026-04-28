package com.my.finmon.ui.portfolio;

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

import com.my.finmon.data.repository.PortfolioRepository.Holding;
import com.my.finmon.data.repository.PortfolioRepository.MaturedBond;
import com.my.finmon.databinding.FragmentHoldingsPageBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One ViewPager2 page on the portfolio screen — the active-holdings list with the
 * collapsible matured-bonds section underneath. Reads from the parent
 * {@link PortfolioFragment}'s {@link PortfolioViewModel}.
 */
public class HoldingsPageFragment extends Fragment {

    private FragmentHoldingsPageBinding binding;
    private PortfolioViewModel viewModel;
    private HoldingsAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHoldingsPageBinding.inflate(inflater, container, false);
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

        viewModel = new ViewModelProvider(requireParentFragment()).get(PortfolioViewModel.class);

        adapter.setOnToggleMaturedListener(viewModel::toggleMaturedExpanded);

        viewModel.holdings().observe(getViewLifecycleOwner(), list -> rebuild());
        viewModel.maturedBonds().observe(getViewLifecycleOwner(), list -> rebuild());
        viewModel.maturedExpanded().observe(getViewLifecycleOwner(), exp -> rebuild());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void rebuild() {
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

        boolean empty = active.isEmpty() && matured.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }
}
