package com.my.finmon.ui.charts;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/** Three-page adapter for the Charts screen. */
final class ChartsPagerAdapter extends FragmentStateAdapter {

    static final int PAGE_VALUE = 0;
    static final int PAGE_GROWTH = 1;
    static final int PAGE_ALLOCATION = 2;
    private static final int PAGE_COUNT = 3;

    ChartsPagerAdapter(@NonNull Fragment host) {
        super(host);
    }

    @Override
    public int getItemCount() {
        return PAGE_COUNT;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case PAGE_GROWTH: return new GrowthPageFragment();
            case PAGE_ALLOCATION: return new AllocationPageFragment();
            case PAGE_VALUE:
            default: return new ValueChartPageFragment();
        }
    }
}
