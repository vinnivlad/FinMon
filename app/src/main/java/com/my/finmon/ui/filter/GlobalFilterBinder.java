package com.my.finmon.ui.filter;

import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.chip.Chip;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.databinding.ViewGlobalFilterBinding;
import com.my.finmon.ui.filter.GlobalFilterViewModel.CustomRange;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wires the {@link R.layout#view_global_filter} chip rows to a
 * {@link GlobalFilterViewModel}. One instance per Activity — created in
 * {@code MainActivity.onCreate} and held for the Activity's lifetime.
 *
 * <p>The currency chip row is rebuilt whenever
 * {@link GlobalFilterViewModel#availableCurrencies()} changes. The period chips are
 * static and live in the XML; the binder just attaches a listener and keeps the
 * checked chip in sync with the VM.
 */
public final class GlobalFilterBinder {

    private static final DateTimeFormatter CHIP_FMT =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault());
    private static final String CHIP_TAG_ALL = "ALL";
    /** {@code -1} sentinel for the "All" chip — Currency enum can't carry a null tag. */
    private static final long CHIP_ID_BASE = 100_000L;

    private final ViewGlobalFilterBinding binding;
    private final GlobalFilterViewModel vm;
    private final FragmentActivity activity;

    /** Maps each currency-chip's view id to the Currency it represents (or null = All). */
    private final Map<Integer, Currency> chipIdToCurrency = new HashMap<>();

    public GlobalFilterBinder(
            @NonNull ViewGlobalFilterBinding binding,
            @NonNull GlobalFilterViewModel vm,
            @NonNull FragmentActivity activity,
            @NonNull LifecycleOwner lifecycleOwner) {
        this.binding = binding;
        this.vm = vm;
        this.activity = activity;

        wirePeriodChips();
        observeViewModel(lifecycleOwner);
    }

    private void wirePeriodChips() {
        binding.globalPeriodChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            // Custom is handled by its own OnClickListener so re-tapping the chip
            // when it's already selected still reopens the picker (the chip group
            // suppresses state-change events for taps that don't change selection).
            if (id == R.id.globalPeriodChipCustom) return;
            FilterPeriod p = periodFor(id);
            if (p != null) vm.setPeriod(p);
        });
        // Always open the picker on any Custom tap — even if the chip is already
        // checked. Solves the "I picked a range and want to pick a different one,
        // but tapping Custom does nothing" UX trap.
        binding.globalPeriodChipCustom.setOnClickListener(v -> openDateRangePicker());
    }

    private void observeViewModel(@NonNull LifecycleOwner owner) {
        vm.availableCurrencies().observe(owner, this::rebuildCurrencyChips);
        vm.selectedCurrency().observe(owner, this::syncCurrencyChip);
        vm.selectedPeriod().observe(owner, this::syncPeriodChip);
        vm.customRange().observe(owner, this::renderCustomChipText);
    }

    private void rebuildCurrencyChips(@Nullable List<Currency> currencies) {
        // Detach the listener while we mutate the chip group so programmatic adds /
        // setChecked don't ping back through the VM.
        binding.globalCurrencyChips.setOnCheckedStateChangeListener(null);
        binding.globalCurrencyChips.removeAllViews();
        chipIdToCurrency.clear();

        // "All" chip is always present, even when the user holds zero positions —
        // gives them somewhere to land before the first import / trade.
        Chip allChip = inflateChip();
        int allId = (int) CHIP_ID_BASE;
        allChip.setId(allId);
        allChip.setTag(CHIP_TAG_ALL);
        allChip.setText(R.string.chart_currency_all);
        binding.globalCurrencyChips.addView(allChip);
        chipIdToCurrency.put(allId, null);

        if (currencies != null) {
            int idx = 1;
            for (Currency c : currencies) {
                Chip chip = inflateChip();
                int id = (int) (CHIP_ID_BASE + idx++);
                chip.setId(id);
                chip.setText(c.name());
                binding.globalCurrencyChips.addView(chip);
                chipIdToCurrency.put(id, c);
            }
        }

        // Restore the checked state to match the VM, then re-attach the listener.
        syncCurrencyChip(vm.selectedCurrency().getValue());
        binding.globalCurrencyChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            // map.containsKey check guards against stale ids that survive a quick rebuild.
            if (!chipIdToCurrency.containsKey(id)) return;
            vm.setCurrency(chipIdToCurrency.get(id));
        });
    }

    private void syncCurrencyChip(@Nullable Currency currency) {
        Integer matchingId = null;
        for (Map.Entry<Integer, Currency> e : chipIdToCurrency.entrySet()) {
            Currency v = e.getValue();
            if ((v == null && currency == null) || (v != null && v == currency)) {
                matchingId = e.getKey();
                break;
            }
        }
        if (matchingId == null) {
            // Selected currency is no longer available (e.g. that bucket dropped to
            // zero) — fall back to "All" rather than leaving the chip row checked-less.
            vm.setCurrency(null);
            return;
        }
        Chip chip = binding.globalCurrencyChips.findViewById(matchingId);
        if (chip != null && !chip.isChecked()) {
            chip.setChecked(true);
        }
    }

    private void syncPeriodChip(@Nullable FilterPeriod period) {
        if (period == null) return;
        int id = chipIdFor(period);
        if (id == 0) return;
        Chip chip = binding.globalPeriodChips.findViewById(id);
        if (chip != null && !chip.isChecked()) {
            chip.setChecked(true);
        }
    }

    private void renderCustomChipText(@Nullable CustomRange range) {
        Chip chip = binding.globalPeriodChipCustom;
        if (chip == null) return;
        if (range == null) {
            chip.setText(R.string.chart_period_custom);
        } else {
            chip.setText(activity.getString(
                    R.string.chart_custom_range_format,
                    range.from.format(CHIP_FMT),
                    range.to.format(CHIP_FMT)));
        }
    }

    private void openDateRangePicker() {
        MaterialDatePicker.Builder<androidx.core.util.Pair<Long, Long>> builder =
                MaterialDatePicker.Builder.dateRangePicker()
                        .setTitleText(R.string.chart_custom_picker_title);

        CustomRange existing = vm.customRange().getValue();
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
            vm.setCustomRange(from, to);
        });

        // If the user dismisses without picking, snap back to the previously-active
        // period chip so Custom doesn't sit checked with no usable range.
        picker.addOnNegativeButtonClickListener(v -> reselectActivePeriodChip());
        picker.addOnCancelListener(d -> reselectActivePeriodChip());

        picker.show(activity.getSupportFragmentManager(), "global_filter_date_range");
    }

    private void reselectActivePeriodChip() {
        FilterPeriod active = vm.selectedPeriod().getValue();
        if (active == null
                || (active == FilterPeriod.CUSTOM && vm.customRange().getValue() == null)) {
            binding.globalPeriodChipAll.setChecked(true);
            return;
        }
        if (active == FilterPeriod.CUSTOM) return;  // existing custom range still valid
        int id = chipIdFor(active);
        if (id != 0) binding.globalPeriodChips.check(id);
    }

    @Nullable
    private static FilterPeriod periodFor(int chipId) {
        if (chipId == R.id.globalPeriodChip5d) return FilterPeriod.FIVE_DAYS;
        if (chipId == R.id.globalPeriodChip1m) return FilterPeriod.ONE_MONTH;
        if (chipId == R.id.globalPeriodChip6m) return FilterPeriod.SIX_MONTHS;
        if (chipId == R.id.globalPeriodChipYtd) return FilterPeriod.YTD;
        if (chipId == R.id.globalPeriodChip1y) return FilterPeriod.ONE_YEAR;
        if (chipId == R.id.globalPeriodChip5y) return FilterPeriod.FIVE_YEARS;
        if (chipId == R.id.globalPeriodChipAll) return FilterPeriod.ALL_TIME;
        if (chipId == R.id.globalPeriodChipCustom) return FilterPeriod.CUSTOM;
        return null;
    }

    private static int chipIdFor(@NonNull FilterPeriod p) {
        switch (p) {
            case FIVE_DAYS: return R.id.globalPeriodChip5d;
            case ONE_MONTH: return R.id.globalPeriodChip1m;
            case SIX_MONTHS: return R.id.globalPeriodChip6m;
            case YTD: return R.id.globalPeriodChipYtd;
            case ONE_YEAR: return R.id.globalPeriodChip1y;
            case FIVE_YEARS: return R.id.globalPeriodChip5y;
            case ALL_TIME: return R.id.globalPeriodChipAll;
            case CUSTOM: return R.id.globalPeriodChipCustom;
            default: return 0;
        }
    }

    private Chip inflateChip() {
        return (Chip) LayoutInflater.from(activity)
                .inflate(R.layout.include_filter_chip, binding.globalCurrencyChips, false);
    }

    private static LocalDate utcMillisToLocalDate(long utcMillis) {
        return Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static long epochUtcMillis(@NonNull LocalDate d) {
        return d.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli();
    }
}
