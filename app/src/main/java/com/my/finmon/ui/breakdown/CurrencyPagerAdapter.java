package com.my.finmon.ui.breakdown;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.my.finmon.data.model.Currency;

import java.util.ArrayList;
import java.util.List;

/** Swaps in one {@link CurrencyPageFragment} per currency tab. */
final class CurrencyPagerAdapter extends FragmentStateAdapter {

    private final List<Currency> currencies = new ArrayList<>();

    CurrencyPagerAdapter(@NonNull Fragment host, @NonNull List<Currency> initial) {
        super(host);
        this.currencies.addAll(initial);
    }

    void setCurrencies(@NonNull List<Currency> list) {
        currencies.clear();
        currencies.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return currencies.size();
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return CurrencyPageFragment.newInstance(currencies.get(position));
    }

    @Override
    public long getItemId(int position) {
        // Use the Currency enum's stable identity so swapping the list doesn't
        // recreate every page (FragmentStateAdapter will diff by id).
        return currencies.get(position).ordinal();
    }

    @Override
    public boolean containsItem(long itemId) {
        for (Currency c : currencies) {
            if (c.ordinal() == itemId) return true;
        }
        return false;
    }
}
