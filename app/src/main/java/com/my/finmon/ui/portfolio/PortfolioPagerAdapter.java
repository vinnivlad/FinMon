package com.my.finmon.ui.portfolio;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * Two-page adapter for the portfolio screen's TabLayout. Page 0 is the holdings list
 * (with the matured-bonds collapsible section); page 1 is the analytics pies.
 */
final class PortfolioPagerAdapter extends FragmentStateAdapter {

    static final int PAGE_HOLDINGS = 0;
    static final int PAGE_ANALYTICS = 1;
    static final int PAGE_COUNT = 2;

    PortfolioPagerAdapter(@NonNull Fragment host) {
        super(host);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case PAGE_ANALYTICS: return new AnalyticsPageFragment();
            case PAGE_HOLDINGS:
            default: return new HoldingsPageFragment();
        }
    }

    @Override
    public int getItemCount() {
        return PAGE_COUNT;
    }
}
