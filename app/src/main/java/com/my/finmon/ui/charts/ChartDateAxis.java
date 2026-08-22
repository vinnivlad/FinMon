package com.my.finmon.ui.charts;

import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Scale-aware x-axis date labels for the time-series charts.
 *
 * <p>Both chart pages plot {@code x = date.toEpochDay() - first.toEpochDay()}, so an x value
 * is a day offset and every decision here is arithmetic on days.
 *
 * <p>Left to itself MPAndroidChart picks its own tick interval, which lands labels on
 * fractional day offsets. That produces two problems at opposite ends of the range. On a
 * five-year window it yields arbitrary-looking exact days ("14 Apr, 6 Dec, 17 Jun") whose day
 * component carries no information at that zoom. On a five-day window it asks for more ticks
 * than there are days and the same date renders twice — not two times of day, just two
 * fractional offsets rounding into one calendar date.
 *
 * <p>The fix is to choose a label <em>unit</em> from the window span and then force a tick
 * count low enough that consecutive ticks are at least one unit apart. That spacing rule is
 * what prevents repeats: two ticks a month apart can't format to the same month, and a year
 * apart can't format to the same year.
 *
 * <p><b>The window is the visible one, not the loaded one.</b> These charts pinch-zoom, and a
 * 5y chart zoomed into a week is a day-band chart while it's zoomed. So the band is recomputed
 * from {@link LineChart#getLowestVisibleX()}/{@link LineChart#getHighestVisibleX()} on every
 * gesture, and the formatter reads whatever band is current rather than closing over one.
 *
 * <p>Ticks stay evenly spaced across the window rather than snapping to the 1st of the month
 * or of January. Snapping isn't reachable through MPAndroidChart's axis renderer, which
 * derives label positions from the axis range and its own "nice number" interval; the labels
 * would have to be drawn by hand to sit on calendar boundaries. Even spacing reads as regular
 * anyway once the day component is gone, which is the actual complaint.
 */
final class ChartDateAxis {

    /** Bands, in days of visible span. Above the last one, labels are bare years. */
    private static final long DAY_BAND_MAX = 180;      // ~6 months
    private static final long MONTH_BAND_MAX = 1095;   // ~3 years

    /** Most ticks a phone-width axis fits before short labels start colliding. */
    private static final int MAX_TICKS_NARROW = 7;
    /** Month and year labels are wider, so they get a lower ceiling. */
    private static final int MAX_TICKS_WIDE = 6;

    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("MMM d", Locale.getDefault());
    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("MMM", Locale.getDefault());
    private static final DateTimeFormatter MONTH_YEAR_FMT =
            DateTimeFormatter.ofPattern("MMM ''yy", Locale.getDefault());
    private static final DateTimeFormatter YEAR_FMT =
            DateTimeFormatter.ofPattern("yyyy", Locale.getDefault());

    private enum Band { DAY, MONTH, YEAR }

    private ChartDateAxis() {}

    /**
     * Installs scale-aware labelling on {@code chart}. {@code first} must be the date the
     * caller used as the x origin, and {@code last} the date of its final point.
     *
     * <p>Call this <em>after</em> {@code setData} — the visible-range read needs the chart's
     * transformer to reflect the new series, and zoom survives a data swap.
     */
    static void apply(@NonNull LineChart chart, @NonNull LocalDate first, @NonNull LocalDate last) {
        Ticker ticker = new Ticker(chart, first, Math.max(0, last.toEpochDay() - first.toEpochDay()));
        chart.getXAxis().setValueFormatter(ticker.formatter());
        chart.setOnChartGestureListener(ticker);
        ticker.retick();
    }

    /**
     * Holds the band currently in effect. Lives as long as the chart's listener does, so the
     * formatter can read the band each draw instead of capturing the one that was true when
     * the data was set.
     */
    private static final class Ticker implements OnChartGestureListener {

        private final LineChart chart;
        private final LocalDate first;
        private final long fullSpan;

        /** Set by {@link #retick()}; read by the formatter on every label. */
        private DateTimeFormatter fmt = DAY_FMT;

        Ticker(LineChart chart, LocalDate first, long fullSpan) {
            this.chart = chart;
            this.first = first;
            this.fullSpan = fullSpan;
        }

        @NonNull
        ValueFormatter formatter() {
            return new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    return first.plusDays(Math.round(value)).format(fmt);
                }
            };
        }

        /** Re-derives band, formatter and tick count from whatever is on screen right now. */
        void retick() {
            float lo = 0f;
            float hi = fullSpan;
            // Before the first layout the viewport has no width and these come back as 0/NaN;
            // the full range is the right answer then anyway, since nothing is zoomed yet.
            if (chart.getData() != null) {
                float visLo = chart.getLowestVisibleX();
                float visHi = chart.getHighestVisibleX();
                if (!Float.isNaN(visLo) && !Float.isNaN(visHi) && visHi > visLo) {
                    lo = visLo;
                    hi = visHi;
                }
            }

            // Kept fractional on purpose. Forced ticks divide the *real* visible range, so
            // rounding the span up here would let the derived spacing fall below one unit
            // and put the duplicate labels straight back.
            double span = hi - lo;
            Band band = bandFor(span);
            fmt = formatterFor(band, first.plusDays(Math.round(lo)), first.plusDays(Math.round(hi)));

            XAxis axis = chart.getXAxis();
            // floor(span / unit) + 1 is how many ticks fit at one unit apart or more; the
            // ceiling takes over on wide windows. Forced spacing is span / (ticks - 1), which
            // this keeps >= one unit, so no two ticks can format to the same label.
            int ticks = (int) Math.min(maxTicksFor(band), Math.floor(span / unitDaysFor(band)) + 1);
            if (ticks < 2) {
                // Window narrower than a single unit — one label, and unforced, since a
                // forced count divides by (count - 1) and would blow up on 1.
                axis.setLabelCount(1, false);
                return;
            }
            axis.setLabelCount(ticks, true);
        }

        @Override
        public void onChartScale(MotionEvent me, float scaleX, float scaleY) { retick(); }

        @Override
        public void onChartTranslate(MotionEvent me, float dX, float dY) {
            // Panning doesn't change the span, but it does change which dates are on screen —
            // and that decides whether month labels need their year.
            retick();
        }

        @Override
        public void onChartDoubleTapped(MotionEvent me) { retick(); }

        @Override
        public void onChartFling(MotionEvent me1, MotionEvent me2, float vX, float vY) { retick(); }

        @Override
        public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture gesture) {
            retick();
        }

        @Override
        public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture gesture) {}

        @Override
        public void onChartLongPressed(MotionEvent me) {}

        @Override
        public void onChartSingleTapped(MotionEvent me) {}
    }

    private static Band bandFor(double spanDays) {
        if (spanDays <= DAY_BAND_MAX) return Band.DAY;
        if (spanDays <= MONTH_BAND_MAX) return Band.MONTH;
        return Band.YEAR;
    }

    private static DateTimeFormatter formatterFor(
            @NonNull Band band, @NonNull LocalDate from, @NonNull LocalDate to) {
        switch (band) {
            case DAY:
                return DAY_FMT;
            case MONTH:
                // A window that crosses a year boundary needs the year: a 1y window opens and
                // closes in the same month, so bare "Aug" would appear at both ends. A window
                // inside one year doesn't — there the year is identical on every label and
                // only adds noise, which is what YTD would otherwise look like.
                return from.getYear() == to.getYear() ? MONTH_FMT : MONTH_YEAR_FMT;
            default:
                return YEAR_FMT;
        }
    }

    private static long unitDaysFor(@NonNull Band band) {
        switch (band) {
            case DAY:   return 1;
            case MONTH: return 30;
            default:    return 365;
        }
    }

    private static int maxTicksFor(@NonNull Band band) {
        return band == Band.DAY ? MAX_TICKS_NARROW : MAX_TICKS_WIDE;
    }
}
