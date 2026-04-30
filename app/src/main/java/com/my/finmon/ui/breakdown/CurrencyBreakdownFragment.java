package com.my.finmon.ui.breakdown;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.databinding.FragmentCurrencyBreakdownBinding;
import com.my.finmon.ui.breakdown.CurrencyBreakdownViewModel.CustomRange;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
        viewModel.customRange().observe(getViewLifecycleOwner(), this::renderCustomChipText);
    }

    private static final DateTimeFormatter CHIP_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

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
            if (checked == R.id.chipCustom) {
                openDateRangePicker();
                return;
            }
            Period p;
            if (checked == R.id.chip1m) p = Period.ONE_MONTH;
            else if (checked == R.id.chip6m) p = Period.SIX_MONTHS;
            else if (checked == R.id.chipYtd) p = Period.YTD;
            else if (checked == R.id.chip1y) p = Period.ONE_YEAR;
            else if (checked == R.id.chip5y) p = Period.FIVE_YEARS;
            else if (checked == R.id.chipAll) p = Period.ALL_TIME;
            else return;
            viewModel.setPeriod(p);
        });
    }

    private void openDateRangePicker() {
        MaterialDatePicker.Builder<androidx.core.util.Pair<Long, Long>> builder =
                MaterialDatePicker.Builder.dateRangePicker()
                        .setTitleText(R.string.chart_custom_picker_title);

        // Pre-fill with the active range if one's already set.
        CustomRange existing = viewModel.customRange().getValue();
        if (existing != null) {
            builder.setSelection(new androidx.core.util.Pair<>(
                    epochUtcMillis(existing.from),
                    epochUtcMillis(existing.to)));
        }

        MaterialDatePicker<androidx.core.util.Pair<Long, Long>> picker = builder.build();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null || selection.first == null || selection.second == null) return;
            LocalDate from = utcMillisToLocalDate(selection.first);
            LocalDate to = utcMillisToLocalDate(selection.second);
            viewModel.setCustomRange(from, to);
        });

        // Cancel-without-pick: snap back to the previously-active period chip so the
        // Custom chip doesn't sit checked with a stale/empty range.
        picker.addOnNegativeButtonClickListener(v -> reselectActivePeriodChip());
        picker.addOnCancelListener(d -> reselectActivePeriodChip());

        picker.show(getChildFragmentManager(), "breakdown_date_range");
    }

    private void reselectActivePeriodChip() {
        Period active = viewModel.period().getValue();
        if (active == null || active == Period.CUSTOM
                && viewModel.customRange().getValue() == null) {
            binding.chipAll.setChecked(true);
            return;
        }
        if (active == Period.CUSTOM) return;  // existing custom range still valid
        int id = chipIdFor(active);
        if (id != 0) binding.periodChips.check(id);
    }

    private int chipIdFor(@NonNull Period p) {
        switch (p) {
            case ONE_MONTH: return R.id.chip1m;
            case SIX_MONTHS: return R.id.chip6m;
            case YTD: return R.id.chipYtd;
            case ONE_YEAR: return R.id.chip1y;
            case FIVE_YEARS: return R.id.chip5y;
            case ALL_TIME: return R.id.chipAll;
            case CUSTOM: return R.id.chipCustom;
            default: return 0;
        }
    }

    private void renderCustomChipText(@Nullable CustomRange range) {
        if (binding == null) return;
        if (range == null) {
            binding.chipCustom.setText(R.string.chart_period_custom);
        } else {
            binding.chipCustom.setText(getString(
                    R.string.chart_custom_range_format,
                    range.from.format(CHIP_FMT),
                    range.to.format(CHIP_FMT)));
        }
    }

    /** Material's DateRangePicker uses UTC-midnight epoch millis. Convert without
     *  picking up the device's timezone offset. */
    private static LocalDate utcMillisToLocalDate(long utcMillis) {
        return Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static long epochUtcMillis(@NonNull LocalDate d) {
        return d.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli();
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
