package com.my.finmon.ui.chart;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.databinding.FragmentChartBinding;
import com.my.finmon.ui.chart.ChartViewModel.ChartData;
import com.my.finmon.ui.chart.ChartViewModel.CustomRange;
import com.my.finmon.ui.chart.ChartViewModel.PeriodTotals;
import com.my.finmon.ui.chart.ChartViewModel.Point;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ChartFragment extends Fragment {

    private FragmentChartBinding binding;
    private ChartViewModel viewModel;

    private static final DateTimeFormatter X_LABEL_FMT = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter CHIP_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final DecimalFormat SIGNED_PCT = buildFormat("+0.0'%';-0.0'%'");

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentChartBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        configureChart();

        viewModel = new ViewModelProvider(this, ChartViewModel.factory(requireContext()))
                .get(ChartViewModel.class);

        wireCurrencyChips();
        wirePeriodChips();

        viewModel.data().observe(getViewLifecycleOwner(), this::render);
        viewModel.customRange().observe(getViewLifecycleOwner(), this::renderCustomChipText);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void wireCurrencyChips() {
        binding.currencyChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            Currency picked = null;
            if (id == R.id.currencyChipUsd) picked = Currency.USD;
            else if (id == R.id.currencyChipEur) picked = Currency.EUR;
            else if (id == R.id.currencyChipUah) picked = Currency.UAH;
            // currencyChipAll → null (the FX-converted view)
            viewModel.setCurrency(picked);
        });
    }

    private void wirePeriodChips() {
        binding.periodChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.periodChipCustom) {
                openDateRangePicker();
                return;
            }
            ChartPeriod period;
            if (id == R.id.periodChip5d) period = ChartPeriod.FIVE_DAYS;
            else if (id == R.id.periodChip1m) period = ChartPeriod.ONE_MONTH;
            else if (id == R.id.periodChip6m) period = ChartPeriod.SIX_MONTHS;
            else if (id == R.id.periodChipYtd) period = ChartPeriod.YTD;
            else if (id == R.id.periodChip1y) period = ChartPeriod.ONE_YEAR;
            else if (id == R.id.periodChip5y) period = ChartPeriod.FIVE_YEARS;
            else if (id == R.id.periodChipAll) period = ChartPeriod.ALL_TIME;
            else return;
            viewModel.setPeriod(period);
        });
    }

    private void openDateRangePicker() {
        MaterialDatePicker.Builder<androidx.core.util.Pair<Long, Long>> builder =
                MaterialDatePicker.Builder.dateRangePicker()
                        .setTitleText(R.string.chart_custom_picker_title);

        // Pre-fill with the current custom range if one's already set.
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

        // If the user dismisses without picking, snap back to whatever period was
        // active before they tapped Custom — otherwise the Custom chip stays selected
        // with no range and the chart would render an empty/wrong window.
        picker.addOnNegativeButtonClickListener(v -> reselectActivePeriodChip());
        picker.addOnCancelListener(d -> reselectActivePeriodChip());

        picker.show(getChildFragmentManager(), "chart_date_range");
    }

    private void reselectActivePeriodChip() {
        ChartPeriod active = viewModel.selectedPeriod().getValue();
        if (active == null || active == ChartPeriod.CUSTOM
                && viewModel.customRange().getValue() == null) {
            // No prior selection — fall back to ALL_TIME.
            binding.periodChipAll.setChecked(true);
            return;
        }
        if (active == ChartPeriod.CUSTOM) return;  // existing custom range still valid
        int id = chipIdFor(active);
        if (id != 0) binding.periodChips.check(id);
    }

    private int chipIdFor(@NonNull ChartPeriod p) {
        switch (p) {
            case FIVE_DAYS: return R.id.periodChip5d;
            case ONE_MONTH: return R.id.periodChip1m;
            case SIX_MONTHS: return R.id.periodChip6m;
            case YTD: return R.id.periodChipYtd;
            case ONE_YEAR: return R.id.periodChip1y;
            case FIVE_YEARS: return R.id.periodChip5y;
            case ALL_TIME: return R.id.periodChipAll;
            case CUSTOM: return R.id.periodChipCustom;
            default: return 0;
        }
    }

    private void renderCustomChipText(@Nullable CustomRange range) {
        if (binding == null) return;
        if (range == null) {
            binding.periodChipCustom.setText(R.string.chart_period_custom);
        } else {
            binding.periodChipCustom.setText(getString(
                    R.string.chart_custom_range_format,
                    range.from.format(CHIP_FMT),
                    range.to.format(CHIP_FMT)));
        }
    }

    private void configureChart() {
        binding.chart.getDescription().setEnabled(false);
        binding.chart.setNoDataText("");
        binding.chart.setPinchZoom(true);
        binding.chart.setDragEnabled(true);
        binding.chart.setScaleEnabled(true);

        XAxis x = binding.chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setGranularity(1f);

        binding.chart.getAxisRight().setEnabled(false);
        YAxis y = binding.chart.getAxisLeft();
        y.setDrawGridLines(true);

        Legend legend = binding.chart.getLegend();
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.LEFT);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
    }

    private void render(@Nullable ChartData cd) {
        boolean empty = (cd == null || cd.points.isEmpty());
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.fxGapHint.setVisibility(cd != null && cd.hasAnyGaps ? View.VISIBLE : View.GONE);

        renderTotalsCard(cd);

        if (empty) {
            binding.chart.clear();
            binding.chart.invalidate();
            return;
        }

        List<Entry> valueEntries = new ArrayList<>(cd.points.size());
        List<Entry> investedEntries = new ArrayList<>(cd.points.size());
        List<Integer> circleColors = new ArrayList<>(cd.points.size());
        final LocalDate x0 = cd.points.get(0).date;

        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (Point p : cd.points) {
            float x = p.date.toEpochDay() - x0.toEpochDay();
            float v = p.value.floatValue();
            float i = p.invested.floatValue();
            valueEntries.add(new Entry(x, v));
            investedEntries.add(new Entry(x, i));
            if (v < minY) minY = v;
            if (i < minY) minY = i;
            if (v > maxY) maxY = v;
            if (i > maxY) maxY = i;
            int color = p.hasFxGaps
                    ? ContextCompat.getColor(requireContext(), R.color.pnl_negative)
                    : ContextCompat.getColor(requireContext(), R.color.pnl_neutral);
            circleColors.add(color);
        }

        // Fit y-axis to data so the gap between Value and Invested stays visible.
        float range = maxY - minY;
        float pad = range > 0 ? range * 0.15f : Math.max(1f, Math.abs(maxY) * 0.05f);
        binding.chart.getAxisLeft().setAxisMinimum(minY - pad);
        binding.chart.getAxisLeft().setAxisMaximum(maxY + pad);

        int valueColor = ContextCompat.getColor(requireContext(), R.color.pnl_positive);
        int investedColor = ContextCompat.getColor(requireContext(), R.color.pnl_neutral);

        LineDataSet valueSet = new LineDataSet(valueEntries, getString(R.string.chart_line_value));
        valueSet.setColor(valueColor);
        valueSet.setLineWidth(2f);
        // Only draw circles when we have FX-gap signal to convey (All-currency view).
        boolean drawCircles = cd.hasAnyGaps || circlesContainGap(circleColors);
        valueSet.setDrawCircles(drawCircles);
        valueSet.setCircleRadius(3f);
        if (drawCircles) {
            valueSet.setCircleColors(circleColors);
            valueSet.setDrawCircleHole(false);
        }
        valueSet.setDrawValues(false);
        valueSet.setMode(LineDataSet.Mode.LINEAR);

        LineDataSet investedSet = new LineDataSet(investedEntries, getString(R.string.chart_line_invested));
        investedSet.setColor(investedColor);
        investedSet.setLineWidth(2f);
        investedSet.setDrawCircles(false);
        investedSet.setDrawValues(false);
        investedSet.setMode(LineDataSet.Mode.LINEAR);
        investedSet.enableDashedLine(12f, 8f, 0f);

        List<ILineDataSet> sets = new ArrayList<>();
        sets.add(investedSet);
        sets.add(valueSet);

        binding.chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return x0.plusDays((long) value).format(X_LABEL_FMT);
            }
        });

        binding.chart.setData(new LineData(sets));
        binding.chart.notifyDataSetChanged();
        binding.chart.invalidate();
    }

    private void renderTotalsCard(@Nullable ChartData cd) {
        if (binding == null) return;
        PeriodTotals t = (cd == null) ? null : cd.periodTotals();
        if (t == null) {
            binding.totalsCard.setVisibility(View.GONE);
            return;
        }
        binding.totalsCard.setVisibility(View.VISIBLE);

        String ccy = t.currency.name();
        binding.totalAmount.setText(MONEY.format(t.valueEnd) + " " + ccy);
        binding.totalInvested.setText(getString(
                R.string.totals_invested_label,
                MONEY.format(t.investedEnd) + " " + ccy));

        StringBuilder pnl = new StringBuilder();
        pnl.append(getString(R.string.chart_period_pnl_label))
                .append(": ")
                .append(SIGNED_MONEY.format(t.periodPnl))
                .append(' ')
                .append(ccy);
        if (t.periodPnlPct != null) {
            pnl.append(" (").append(SIGNED_PCT.format(t.periodPnlPct)).append(')');
        }
        binding.totalPnl.setText(pnl.toString());
        int color = t.periodPnl.signum() > 0
                ? R.color.pnl_positive
                : (t.periodPnl.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.totalPnl.setTextColor(ContextCompat.getColor(requireContext(), color));
    }

    private boolean circlesContainGap(@NonNull List<Integer> circleColors) {
        int gapColor = ContextCompat.getColor(requireContext(), R.color.pnl_negative);
        for (int c : circleColors) if (c == gapColor) return true;
        return false;
    }

    /**
     * Material's DateRangePicker hands back UTC-midnight epoch millis. Convert to a
     * LocalDate without picking up the device's timezone offset.
     */
    private static LocalDate utcMillisToLocalDate(long utcMillis) {
        return Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static long epochUtcMillis(@NonNull LocalDate d) {
        return d.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli();
    }
}
