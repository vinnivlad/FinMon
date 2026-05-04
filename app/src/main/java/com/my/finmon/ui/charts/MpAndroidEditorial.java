package com.my.finmon.ui.charts;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.my.finmon.R;

/**
 * Editorial polish for MPAndroidChart surfaces. Centralizes axis colors, grid
 * colors, gridline-dash, typography, and legend visibility so each Charts page
 * doesn't redefine the same handful of styling calls. Keeps the visual contract
 * "ink labels on rule grid, JetBrains Mono on numbers, no Material defaults"
 * in one place.
 */
public final class MpAndroidEditorial {

    private MpAndroidEditorial() {}

    /**
     * Apply the editorial axis/grid look to a {@link LineChart}. Caller still
     * owns axis-specific concerns: value formatters, axis range, x-tick spacing.
     */
    public static void applyLineChart(@NonNull LineChart chart) {
        Context ctx = chart.getContext();
        int inkMute = ContextCompat.getColor(ctx, R.color.fm_ink_mute);
        int rule = ContextCompat.getColor(ctx, R.color.fm_rule);
        Typeface inter = safeFont(ctx, R.font.inter);
        Typeface mono = safeFont(ctx, R.font.jetbrains_mono);

        chart.getDescription().setEnabled(false);
        chart.setNoDataText("");
        chart.getLegend().setEnabled(false);

        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setDrawAxisLine(false);
        x.setTextColor(inkMute);
        x.setTextSize(10f);
        if (inter != null) x.setTypeface(inter);

        YAxis y = chart.getAxisLeft();
        y.setDrawGridLines(true);
        y.setDrawAxisLine(false);
        y.setGridColor(rule);
        y.setGridLineWidth(0.5f);
        y.enableGridDashedLine(4f, 4f, 0f);
        y.setTextColor(inkMute);
        y.setTextSize(10f);
        // Y axis = numbers → mono.
        if (mono != null) y.setTypeface(mono);

        chart.getAxisRight().setEnabled(false);
    }

    /**
     * Apply the editorial pie look — transparent hole (no donut center fill),
     * cream on-slice labels, no description, no inner shadow rings. Slice colors,
     * legend visibility, and entry labels are left to the caller (they vary by
     * filter mode).
     */
    static void applyPieChart(@NonNull PieChart pie) {
        Context ctx = pie.getContext();
        int bg = ContextCompat.getColor(ctx, R.color.fm_bg);

        pie.getDescription().setEnabled(false);
        pie.setNoDataText("");
        pie.setExtraOffsets(8f, 8f, 8f, 8f);
        pie.setDrawHoleEnabled(true);
        pie.setHoleColor(Color.TRANSPARENT);
        pie.setTransparentCircleAlpha(0);
        pie.setHoleRadius(46f);
        pie.setRotationEnabled(true);
        pie.setEntryLabelColor(bg);
        pie.setEntryLabelTextSize(10f);
        Typeface inter = safeFont(ctx, R.font.inter);
        if (inter != null) pie.setEntryLabelTypeface(inter);
    }

    private static Typeface safeFont(@NonNull Context ctx, int resId) {
        try {
            return ResourcesCompat.getFont(ctx, resId);
        } catch (Exception ignored) {
            // Downloadable fonts can be momentarily unresolvable on first launch;
            // fall back to system typeface rather than crashing the chart.
            return null;
        }
    }
}
