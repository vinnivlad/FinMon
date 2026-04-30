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

import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.my.finmon.R;
import com.my.finmon.databinding.FragmentValueChartBinding;
import com.my.finmon.ui.charts.ValueChartViewModel.ChartData;
import com.my.finmon.ui.charts.ValueChartViewModel.PeriodTotals;
import com.my.finmon.ui.charts.ValueChartViewModel.Point;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

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
        binding = FragmentValueChartBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        configureChart();

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

        float range = maxY - minY;
        float pad = range > 0 ? range * 0.15f : Math.max(1f, Math.abs(maxY) * 0.05f);
        binding.chart.getAxisLeft().setAxisMinimum(minY - pad);
        binding.chart.getAxisLeft().setAxisMaximum(maxY + pad);

        int valueColor = ContextCompat.getColor(requireContext(), R.color.pnl_positive);
        int investedColor = ContextCompat.getColor(requireContext(), R.color.pnl_neutral);

        LineDataSet valueSet = new LineDataSet(valueEntries, getString(R.string.chart_line_value));
        valueSet.setColor(valueColor);
        valueSet.setLineWidth(2f);
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
}
