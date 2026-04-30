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
import com.my.finmon.data.model.Currency;
import com.my.finmon.databinding.FragmentCurrencyBreakdownBinding;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

import java.util.Collections;
import java.util.List;

/**
 * Parent of the Breakdown screen. Hosts currency tabs + a ViewPager2 of per-currency
 * pages. The period filter and currency selector both come from the Activity-scoped
 * {@link GlobalFilterViewModel} — picking a specific currency in the global filter
 * auto-jumps the ViewPager to that tab.
 */
public class CurrencyBreakdownFragment extends Fragment {

    private FragmentCurrencyBreakdownBinding binding;
    private CurrencyBreakdownViewModel viewModel;
    private GlobalFilterViewModel globalFilter;
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

        viewModel = new ViewModelProvider(
                this, CurrencyBreakdownViewModel.factory(requireContext()))
                .get(CurrencyBreakdownViewModel.class);
        globalFilter = new ViewModelProvider(
                requireActivity(), GlobalFilterViewModel.factory(requireContext()))
                .get(GlobalFilterViewModel.class);

        pagerAdapter = new CurrencyPagerAdapter(this, Collections.emptyList());
        binding.pager.setAdapter(pagerAdapter);

        viewModel.currencies().observe(getViewLifecycleOwner(), this::renderCurrencies);
        // When the global currency filter narrows to a specific currency, jump the
        // pager to that tab so the user lands on the page they implicitly asked for.
        globalFilter.selectedCurrency().observe(getViewLifecycleOwner(), this::syncPagerToFilter);
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

        // Re-apply the global filter selection after the pager is repopulated — order
        // matters (mediator first, then setCurrentItem) so the tab selection sticks.
        syncPagerToFilter(globalFilter.selectedCurrency().getValue());
    }

    private void syncPagerToFilter(@Nullable Currency selected) {
        if (binding == null || pagerAdapter == null) return;
        if (selected == null) return;  // "All" — keep whatever tab the user is on.
        List<Currency> list = pagerAdapter.currencies();
        int index = list.indexOf(selected);
        if (index < 0) return;  // currency isn't in the user's holdings — nothing to do.
        if (binding.pager.getCurrentItem() != index) {
            binding.pager.setCurrentItem(index, /* smoothScroll */ false);
        }
    }
}
