package com.my.finmon.ui.charts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.my.finmon.R;
import com.my.finmon.databinding.FragmentChartsBinding;

/**
 * Charts screen — TabLayout + ViewPager2 over three pages: Value (portfolio
 * value vs. invested over time), Growth (period-only %-return curve, Phase 2),
 * and Allocation (composition pies). All three pages react to the global filter.
 */
public class ChartsFragment extends Fragment {

    private FragmentChartsBinding binding;
    private TabLayoutMediator tabMediator;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentChartsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.chartsPager.setAdapter(new ChartsPagerAdapter(this));
        tabMediator = new TabLayoutMediator(
                binding.chartsTabs, binding.chartsPager,
                (TabLayout.Tab tab, int position) -> {
                    int titleRes;
                    switch (position) {
                        case ChartsPagerAdapter.PAGE_GROWTH:
                            titleRes = R.string.charts_tab_growth;
                            break;
                        case ChartsPagerAdapter.PAGE_ALLOCATION:
                            titleRes = R.string.charts_tab_allocation;
                            break;
                        case ChartsPagerAdapter.PAGE_VALUE:
                        default:
                            titleRes = R.string.charts_tab_value;
                            break;
                    }
                    tab.setText(titleRes);
                });
        tabMediator.attach();
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
