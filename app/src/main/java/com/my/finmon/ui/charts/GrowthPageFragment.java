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

import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.my.finmon.R;
import com.my.finmon.databinding.FragmentGrowthChartBinding;
import com.my.finmon.ui.charts.GrowthChartViewModel.GrowthData;
import com.my.finmon.ui.charts.GrowthChartViewModel.Point;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Charts → Growth page. Plots period-only % return (anchored at 0% on the left
 * edge of the active window). Above-zero segments are green, below-zero are red,
 * and a horizontal LimitLine at 0% gives a clear visual reference.
 */
public class GrowthPageFragment extends Fragment {

    private static final DateTimeFormatter X_LABEL_FMT = DateTimeFormatter.ofPattern("MMM d");

    private static final DecimalFormat SIGNED_PCT = buildFormat("+0.00'%';-0.00'%'");
    private static final DecimalFormat AXIS_PCT = buildFormat("+0.0;-0.0");

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }

    private FragmentGrowthChartBinding binding;
    private GrowthChartViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentGrowthChartBinding.inflate(inflater, container, false);
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
                this, GrowthChartViewModel.factory(requireContext(), globalFilter))
                .get(GrowthChartViewModel.class);

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
        binding.growthChart.getDescription().setEnabled(false);
        binding.growthChart.setNoDataText("");
        binding.growthChart.setPinchZoom(true);
        binding.growthChart.setDragEnabled(true);
        binding.growthChart.setScaleEnabled(true);
        binding.growthChart.getLegend().setEnabled(false);

        XAxis x = binding.growthChart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setGranularity(1f);

        binding.growthChart.getAxisRight().setEnabled(false);
        YAxis y = binding.growthChart.getAxisLeft();
        y.setDrawGridLines(true);
        // Y-axis label = signed percentage (+1.2 / -3.4) — % suffix from the chart
        // is implicit since the entire axis is "growth %".
        y.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return AXIS_PCT.format(value) + "%";
            }
        });

        // Zero-line LimitLine — visual anchor for the "no growth" baseline. Drawn
        // dashed in colorOutline so it reads as scaffolding rather than data.
        LimitLine zero = new LimitLine(0f);
        zero.setLineColor(ContextCompat.getColor(requireContext(), R.color.pnl_neutral));
        zero.setLineWidth(1f);
        zero.enableDashedLine(8f, 6f, 0f);
        zero.setLabel("");
        y.removeAllLimitLines();
        y.addLimitLine(zero);
    }

    private void render(@Nullable GrowthData gd) {
        if (binding == null) return;
        boolean empty = (gd == null || gd.points.size() < 2);
        binding.growthEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.growthFxGapHint.setVisibility(
                gd != null && gd.hasFxGaps ? View.VISIBLE : View.GONE);

        renderTotalsCard(gd);

        if (empty) {
            binding.growthChart.clear();
            binding.growthChart.invalidate();
            return;
        }

        // Walk points and emit a new LineDataSet at every zero-crossing so each
        // contiguous above-zero / below-zero run is its own colored line. Putting
        // all positive points into one dataset would have MPAndroidChart connect
        // them through any negative gap, drawing a flat-ish ghost on top of the
        // zero line during the dip.
        final LocalDate x0 = gd.points.get(0).date;
        int posColor = ContextCompat.getColor(requireContext(), R.color.pnl_positive);
        int negColor = ContextCompat.getColor(requireContext(), R.color.pnl_negative);

        List<ILineDataSet> sets = new ArrayList<>();
        List<Entry> current = new ArrayList<>();
        boolean currentPositive = true;

        for (int i = 0; i < gd.points.size(); i++) {
            Point p = gd.points.get(i);
            float x = p.date.toEpochDay() - x0.toEpochDay();
            float y = p.pct.floatValue();

            if (current.isEmpty()) {
                current.add(new Entry(x, y));
                currentPositive = y >= 0f;
                continue;
            }

            Entry last = current.get(current.size() - 1);
            float lastY = last.getY();
            boolean crosses = (lastY > 0f && y < 0f) || (lastY < 0f && y > 0f);
            if (crosses) {
                // Linear-interpolate the zero crossing and close the segment there.
                float lastX = last.getX();
                float t = -lastY / (y - lastY);
                float zx = lastX + t * (x - lastX);
                current.add(new Entry(zx, 0f));
                sets.add(buildSet(current, currentPositive ? posColor : negColor));
                // Start a new segment from the same zero point so the two colored
                // lines meet exactly on the y=0 crossing.
                current = new ArrayList<>();
                current.add(new Entry(zx, 0f));
                current.add(new Entry(x, y));
                currentPositive = y >= 0f;
            } else {
                current.add(new Entry(x, y));
            }
        }
        if (!current.isEmpty()) {
            sets.add(buildSet(current, currentPositive ? posColor : negColor));
        }

        binding.growthChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return x0.plusDays((long) value).format(X_LABEL_FMT);
            }
        });

        binding.growthChart.setData(new LineData(sets));
        binding.growthChart.notifyDataSetChanged();
        binding.growthChart.invalidate();
    }

    @NonNull
    private static LineDataSet buildSet(@NonNull List<Entry> entries, int color) {
        LineDataSet set = new LineDataSet(entries, "");
        set.setColor(color);
        set.setLineWidth(2f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.LINEAR);
        return set;
    }

    private void renderTotalsCard(@Nullable GrowthData gd) {
        if (binding == null) return;
        BigDecimal end = (gd == null) ? null : gd.endPct();
        if (end == null) {
            binding.growthTotalsCard.setVisibility(View.GONE);
            return;
        }
        binding.growthTotalsCard.setVisibility(View.VISIBLE);
        binding.growthHeadline.setText(SIGNED_PCT.format(end));
        int color = end.signum() > 0
                ? R.color.pnl_positive
                : (end.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.growthHeadline.setTextColor(ContextCompat.getColor(requireContext(), color));
    }
}
