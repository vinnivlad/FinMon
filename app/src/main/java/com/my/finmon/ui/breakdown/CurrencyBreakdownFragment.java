package com.my.finmon.ui.breakdown;

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
import com.my.finmon.databinding.FragmentCurrencyBreakdownBinding;

import java.util.Collections;
import java.util.List;

/**
 * Parent of the currency-breakdown screen. Hosts the period filter (chips), currency
 * tabs, and a ViewPager2 that swaps in one {@link CurrencyPageFragment} per non-zero
 * currency. All pages observe this fragment's shared {@link CurrencyBreakdownViewModel}
 * for the active period, so swiping between currencies keeps the filter sticky.
 */
public class CurrencyBreakdownFragment extends Fragment {

    private FragmentCurrencyBreakdownBinding binding;
    private CurrencyBreakdownViewModel viewModel;
    private CurrencyPagerAdapter pagerAdapter;
    private TabLayoutMediator tabMediator;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCurrencyBreakdownBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this, CurrencyBreakdownViewModel.factory(requireContext()))
                .get(CurrencyBreakdownViewModel.class);

        pagerAdapter = new CurrencyPagerAdapter(this, Collections.emptyList());
        binding.pager.setAdapter(pagerAdapter);

        bindChips();

        viewModel.currencies().observe(getViewLifecycleOwner(), this::renderCurrencies);
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

    private void bindChips() {
        // Single-selection ChipGroup: map the checked id to a Period and push to the VM.
        binding.periodChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int checked = checkedIds.get(0);
            Period p;
            if (checked == R.id.chipAll) p = Period.ALL_TIME;
            else if (checked == R.id.chipYtd) p = Period.YTD;
            else if (checked == R.id.chip1m) p = Period.ONE_MONTH;
            else if (checked == R.id.chip1y) p = Period.ONE_YEAR;
            else return;
            viewModel.setPeriod(p);
        });
    }

    private void renderCurrencies(@Nullable List<Currency> list) {
        boolean empty = (list == null || list.isEmpty());
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.currencyTabs.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.pager.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;

        pagerAdapter.setCurrencies(list);

        if (tabMediator != null) tabMediator.detach();
        tabMediator = new TabLayoutMediator(
                binding.currencyTabs,
                binding.pager,
                (TabLayout.Tab tab, int position) -> tab.setText(list.get(position).name()));
        tabMediator.attach();
    }
}
