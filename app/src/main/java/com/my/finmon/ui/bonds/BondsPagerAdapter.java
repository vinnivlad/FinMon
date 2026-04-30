package com.my.finmon.ui.bonds;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/** Two-page adapter for the Bonds screen. */
final class BondsPagerAdapter extends FragmentStateAdapter {

    static final int PAGE_HOLDINGS = 0;
    static final int PAGE_CALENDAR = 1;
    private static final int PAGE_COUNT = 2;

    BondsPagerAdapter(@NonNull Fragment host) {
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
            case PAGE_CALENDAR: return new BondsCalendarPageFragment();
            case PAGE_HOLDINGS:
            default: return new BondsHoldingsPageFragment();
        }
    }
}
