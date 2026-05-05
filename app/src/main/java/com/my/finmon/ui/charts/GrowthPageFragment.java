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
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.my.finmon.R;
import com.my.finmon.databinding.FragmentGrowthChartBinding;
import com.my.finmon.databinding.ItemBestWorstMonthBinding;
import com.my.finmon.ui.charts.GrowthChartViewModel.GrowthData;
import com.my.finmon.ui.charts.GrowthChartViewModel.Point;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Charts → Growth page. Plots period-only % return (anchored at 0% on the left
 * edge of the active window). Above-zero segments are green, below-zero are red,
 * and a horizontal LimitLine at 0% gives a clear visual reference. Below the chart,
 * a "best & worst months" list highlights the strongest and weakest months in the
 * window — month-over-month delta of (value − invested), so capital deposits
 * cancel and the numbers reflect market P&L only.
 */
public class GrowthPageFragment extends Fragment {

    private static final DateTimeFormatter X_LABEL_FMT = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter MONTH_YEAR_FMT =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault());

    private static final DecimalFormat SIGNED_PCT = buildFormat("+0.00'%';-0.00'%'");
    private static final DecimalFormat AXIS_PCT = buildFormat("+0.0;-0.0");
    private static final DecimalFormat SIGNED_WHOLE = buildFormat("+#,##0;-#,##0");

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
        MpAndroidEditorial.applyLineChart(binding.growthChart);
        binding.growthChart.setPinchZoom(true);
        binding.growthChart.setDragEnabled(true);
        binding.growthChart.setScaleEnabled(true);

        YAxis y = binding.growthChart.getAxisLeft();
        y.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return AXIS_PCT.format(value) + "%";
            }
        });

        // Zero-line LimitLine — visual anchor for the "no growth" baseline. Drawn
        // dashed in the editorial outline color so it reads as scaffolding rather
        // than data.
        LimitLine zero = new LimitLine(0f);
        zero.setLineColor(ContextCompat.getColor(requireContext(), R.color.fm_rule_strong));
        zero.setLineWidth(0.8f);
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
        renderBestWorstMonths(gd);

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
        set.setLineWidth(1.6f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.LINEAR);
        // Editorial area fill — segment color at ~10 % alpha. The fill formatter
        // pins the baseline at y=0 so positive segments fill *down* to zero and
        // negative segments fill *up* to zero (instead of MPAndroidChart's default,
        // which is the chart's Y minimum — that would over-fill negative regions).
        set.setDrawFilled(true);
        set.setFillColor(color);
        set.setFillAlpha(25);
        set.setFillFormatter((dataSet, dataProvider) -> 0f);
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

        // Kicker carries the period name so the user knows what window is summarized.
        // Period name is sourced from the global filter; we look it up via the VM
        // by reading the same shared filter VM the page already observes.
        binding.growthLabel.setText(getString(
                R.string.charts_growth_kicker_format, periodLabel()));

        binding.growthHeadline.setText(SIGNED_PCT.format(end));
        int color = end.signum() > 0
                ? R.color.pnl_positive
                : (end.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.growthHeadline.setTextColor(ContextCompat.getColor(requireContext(), color));
    }

    /**
     * Compute month-over-month P&L deltas from the underlying NAV series, then
     * pick the top 2 best (positive) + top 2 worst (negative) months and inflate
     * a row per result. Section is hidden when the window is too short to yield
     * any complete months.
     */
    private void renderBestWorstMonths(@Nullable GrowthData gd) {
        binding.bestWorstList.removeAllViews();
        if (gd == null || gd.points.size() < 2) {
            binding.bestWorstSection.setVisibility(View.GONE);
            return;
        }

        // Bucket points by YearMonth, keeping only the last point in each bucket
        // (latest snapshot of the month). Insertion order = chronological, so a
        // LinkedHashMap preserves it for the diff walk.
        Map<YearMonth, Point> lastInMonth = new LinkedHashMap<>();
        for (Point p : gd.points) {
            lastInMonth.put(YearMonth.from(p.date), p);
        }
        if (lastInMonth.size() < 2) {
            binding.bestWorstSection.setVisibility(View.GONE);
            return;
        }

        // Each month's P&L delta = pnl(this month-end) − pnl(prev month-end), where
        // pnl = value − invested. Capital deposits add equally to both, so they
        // cancel — what's left is market-driven, matching the project's "isolate
        // market P&L from cash flows" core.
        List<MonthDelta> deltas = new ArrayList<>(lastInMonth.size() - 1);
        Point prev = null;
        for (Map.Entry<YearMonth, Point> e : lastInMonth.entrySet()) {
            Point cur = e.getValue();
            if (prev != null) {
                BigDecimal pnlPrev = prev.value.subtract(prev.invested);
                BigDecimal pnlCur = cur.value.subtract(cur.invested);
                deltas.add(new MonthDelta(e.getKey(), pnlCur.subtract(pnlPrev)));
            }
            prev = cur;
        }
        if (deltas.isEmpty()) {
            binding.bestWorstSection.setVisibility(View.GONE);
            return;
        }

        // Top 2 by descending delta (best), bottom 2 by ascending (worst). Filter
        // out near-zero entries from the worst list when they overlap with best
        // (very short windows can produce <4 distinct rows).
        List<MonthDelta> sorted = new ArrayList<>(deltas);
        Collections.sort(sorted, (a, b) -> b.delta.compareTo(a.delta));
        List<MonthDelta> rows = new ArrayList<>(4);
        for (int i = 0; i < Math.min(2, sorted.size()); i++) rows.add(sorted.get(i));
        for (int i = sorted.size() - 1; i >= Math.max(0, sorted.size() - 2); i--) {
            MonthDelta md = sorted.get(i);
            if (rows.contains(md)) continue;  // dedup with best when count < 4
            rows.add(md);
        }

        for (MonthDelta md : rows) {
            inflateMonthRow(md, gd.currency.name());
        }
        binding.bestWorstSection.setVisibility(View.VISIBLE);
    }

    private void inflateMonthRow(@NonNull MonthDelta md, @NonNull String ccy) {
        ItemBestWorstMonthBinding row = ItemBestWorstMonthBinding.inflate(
                LayoutInflater.from(requireContext()), binding.bestWorstList, false);
        row.monthLabel.setText(md.month.atDay(1).format(MONTH_YEAR_FMT));
        // Italic for negative months mirrors the JSX styling cue.
        row.monthLabel.setTypeface(row.monthLabel.getTypeface(),
                md.delta.signum() < 0 ? android.graphics.Typeface.ITALIC : android.graphics.Typeface.NORMAL);
        row.monthValue.setText(SIGNED_WHOLE.format(md.delta) + " " + ccy);
        int color = md.delta.signum() > 0
                ? R.color.pnl_positive
                : (md.delta.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        row.monthValue.setTextColor(ContextCompat.getColor(requireContext(), color));
        binding.bestWorstList.addView(row.getRoot());
    }

    @NonNull
    private String periodLabel() {
        // Pull the period name from the shared filter for the kicker text.
        GlobalFilterViewModel f = new ViewModelProvider(
                requireActivity(), GlobalFilterViewModel.factory(requireContext()))
                .get(GlobalFilterViewModel.class);
        com.my.finmon.ui.filter.FilterPeriod p = f.selectedPeriod().getValue();
        if (p == null) return getString(R.string.chart_period_all);
        switch (p) {
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

    private static final class MonthDelta {
        @NonNull final YearMonth month;
        @NonNull final BigDecimal delta;

        MonthDelta(@NonNull YearMonth month, @NonNull BigDecimal delta) {
            this.month = month;
            this.delta = delta;
        }
    }
}
