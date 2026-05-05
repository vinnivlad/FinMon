package com.my.finmon.ui.charts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.databinding.FragmentValueChartBinding;
import com.my.finmon.ui.charts.ValueChartViewModel.ChartData;
import com.my.finmon.ui.charts.ValueChartViewModel.PeriodTotals;
import com.my.finmon.ui.charts.ValueChartViewModel.Point;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Charts → Value page. Plots portfolio value and invested-capital lines. */
public class ValueChartPageFragment extends Fragment {

    private FragmentValueChartBinding binding;
    private ValueChartViewModel viewModel;

    private static final DateTimeFormatter X_LABEL_FMT = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter MONTH_YEAR_FMT =
            DateTimeFormatter.ofPattern("MMM yyyy");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MMM");

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat WHOLE = buildFormat("#,##0");
    private static final DecimalFormat SIGNED_WHOLE = buildFormat("+#,##0;-#,##0");
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
        binding = FragmentValueChartBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MpAndroidEditorial.applyLineChart(binding.chart);
        binding.chart.setPinchZoom(true);
        binding.chart.setDragEnabled(true);
        binding.chart.setScaleEnabled(true);

        GlobalFilterViewModel globalFilter = new ViewModelProvider(
                requireActivity(), GlobalFilterViewModel.factory(requireContext()))
                .get(GlobalFilterViewModel.class);

        viewModel = new ViewModelProvider(
                this, ValueChartViewModel.factory(requireContext(), globalFilter))
                .get(ValueChartViewModel.class);

        viewModel.data().observe(getViewLifecycleOwner(), this::render);
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

    private void render(@Nullable ChartData cd) {
        boolean empty = (cd == null || cd.points.isEmpty());
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.fxGapHint.setVisibility(cd != null && cd.hasAnyGaps ? View.VISIBLE : View.GONE);

        renderTotalsCard(cd);
        renderReadoutStrip(cd);

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
                    : ContextCompat.getColor(requireContext(), R.color.fm_ink_mute);
            circleColors.add(color);
        }

        float range = maxY - minY;
        float pad = range > 0 ? range * 0.15f : Math.max(1f, Math.abs(maxY) * 0.05f);
        binding.chart.getAxisLeft().setAxisMinimum(minY - pad);
        binding.chart.getAxisLeft().setAxisMaximum(maxY + pad);

        // Editorial palette: Value line in fm_accent (teal), Invested in fm_ink_mute
        // dashed. Both are ink-on-cream readable; the dash separates them without
        // needing a legend.
        int valueColor = ContextCompat.getColor(requireContext(), R.color.fm_accent);
        int investedColor = ContextCompat.getColor(requireContext(), R.color.fm_ink_mute);

        LineDataSet valueSet = new LineDataSet(valueEntries, getString(R.string.chart_line_value));
        valueSet.setColor(valueColor);
        valueSet.setLineWidth(1.6f);
        boolean drawCircles = cd.hasAnyGaps || circlesContainGap(circleColors);
        valueSet.setDrawCircles(drawCircles);
        valueSet.setCircleRadius(2.5f);
        if (drawCircles) {
            valueSet.setCircleColors(circleColors);
            valueSet.setDrawCircleHole(false);
        }
        valueSet.setDrawValues(false);
        valueSet.setMode(LineDataSet.Mode.LINEAR);
        // Editorial fill — soft teal area below the value line. Alpha 20/255 ≈ 8 %
        // (matches the JSX). Fill drops to the chart's bottom by default, which on
        // an auto-zoomed Y-axis sits just under the data range — visually identical
        // to the JSX's "fill to baseline" since Y never reaches 0 here.
        valueSet.setDrawFilled(true);
        valueSet.setFillColor(valueColor);
        valueSet.setFillAlpha(20);

        LineDataSet investedSet = new LineDataSet(investedEntries, getString(R.string.chart_line_invested));
        investedSet.setColor(investedColor);
        investedSet.setLineWidth(1.2f);
        investedSet.setDrawCircles(false);
        investedSet.setDrawValues(false);
        investedSet.setMode(LineDataSet.Mode.LINEAR);
        investedSet.enableDashedLine(8f, 6f, 0f);

        // Render order = list order (last item on top). Value first → its area fill
        // and stroke sit in the back; Invested dashed line draws on top so it's
        // never obscured by the fill.
        List<ILineDataSet> sets = new ArrayList<>();
        sets.add(valueSet);
        sets.add(investedSet);

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

        binding.totalsLabel.setText(getString(
                R.string.charts_value_kicker_format, periodLabel(cd)));

        renderHeadlineSplit(t.valueEnd, t.currency);

        // Invested line: lower-case "Invested" + mono value, mirror of Portfolio.
        binding.totalInvested.setText(getString(
                R.string.totals_invested_label,
                MONEY.format(t.investedEnd) + " " + t.currency.name()));

        // P&L: arrow glyph + signed amount + percent — colored by sign.
        String arrow = t.periodPnl.signum() > 0
                ? getString(R.string.arrow_up)
                : (t.periodPnl.signum() < 0 ? getString(R.string.arrow_down) : "");
        StringBuilder pnlText = new StringBuilder();
        if (!arrow.isEmpty()) pnlText.append(arrow).append(' ');
        pnlText.append(SIGNED_MONEY.format(t.periodPnl));
        if (t.periodPnlPct != null) {
            pnlText.append(" (").append(SIGNED_PCT.format(t.periodPnlPct)).append(')');
        }
        binding.totalPnl.setText(pnlText.toString());
        int color = t.periodPnl.signum() > 0
                ? R.color.pnl_positive
                : (t.periodPnl.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.totalPnl.setTextColor(ContextCompat.getColor(requireContext(), color));
    }

    private void renderHeadlineSplit(@NonNull BigDecimal amount, @NonNull Currency ccy) {
        String formatted = MONEY.format(amount);
        int dot = formatted.lastIndexOf('.');
        String intPart;
        String fracPart;
        if (dot >= 0) {
            intPart = formatted.substring(0, dot);
            fracPart = formatted.substring(dot);
        } else {
            intPart = formatted;
            fracPart = "";
        }
        binding.totalAmountInteger.setText(intPart);
        binding.totalAmountFraction.setText(fracPart);
        binding.totalAmountCurrency.setText(ccy.name());
    }

    private void renderReadoutStrip(@Nullable ChartData cd) {
        if (binding == null) return;
        if (cd == null || cd.points.size() < 2) {
            binding.readoutStrip.setVisibility(View.GONE);
            return;
        }
        binding.readoutStrip.setVisibility(View.VISIBLE);

        // Walk once: track high/low (and their dates), plus first/last for delta.
        Point hi = cd.points.get(0);
        Point lo = cd.points.get(0);
        for (Point p : cd.points) {
            if (p.value.compareTo(hi.value) > 0) hi = p;
            if (p.value.compareTo(lo.value) < 0) lo = p;
        }
        Point first = cd.points.get(0);
        Point last = cd.points.get(cd.points.size() - 1);
        BigDecimal delta = last.value.subtract(first.value);

        String ccy = " " + cd.currency.name();
        binding.readoutHighValue.setText(WHOLE.format(hi.value) + ccy);
        binding.readoutHighSub.setText(hi.date.format(MONTH_YEAR_FMT));

        binding.readoutLowValue.setText(WHOLE.format(lo.value) + ccy);
        binding.readoutLowSub.setText(lo.date.format(MONTH_YEAR_FMT));

        binding.readoutDeltaValue.setText(SIGNED_WHOLE.format(delta) + ccy);
        int deltaColor = delta.signum() > 0
                ? R.color.pnl_positive
                : (delta.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.readoutDeltaValue.setTextColor(
                ContextCompat.getColor(requireContext(), deltaColor));
        binding.readoutDeltaSub.setText(getString(
                R.string.charts_readout_range,
                first.date.format(MONTH_FMT),
                last.date.format(MONTH_FMT)));
    }

    /** Label used inside the totals kicker (e.g. "6m", "Custom range", "All"). */
    @NonNull
    private String periodLabel(@NonNull ChartData cd) {
        if (cd.period == null) return "";
        switch (cd.period) {
            case FIVE_DAYS: return getString(R.string.chart_period_5d);
            case ONE_MONTH: return getString(R.string.chart_period_1m);
            case SIX_MONTHS: return getString(R.string.chart_period_6m);
            case YTD: return getString(R.string.chart_period_ytd);
            case ONE_YEAR: return getString(R.string.chart_period_1y);
            case FIVE_YEARS: return getString(R.string.chart_period_5y);
            case CUSTOM: return getString(R.string.chart_period_custom);
            case ALL_TIME:
            default: return getString(R.string.chart_period_all);
        }
    }

    private boolean circlesContainGap(@NonNull List<Integer> circleColors) {
        int gapColor = ContextCompat.getColor(requireContext(), R.color.pnl_negative);
        for (int c : circleColors) if (c == gapColor) return true;
        return false;
    }
}
