package com.my.finmon.ui.market;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

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
import com.google.android.material.chip.Chip;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.snackbar.Snackbar;
import com.my.finmon.R;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.remote.yahoo.YahooClient.MarketSeries;
import com.my.finmon.data.remote.yahoo.YahooClient.SeriesPoint;
import com.my.finmon.databinding.FragmentMarketBinding;
import com.my.finmon.ui.addtrade.AssetSuggestion;
import com.my.finmon.ui.addtrade.AssetSuggestionAdapter;
import com.my.finmon.ui.charts.MpAndroidEditorial;
import com.my.finmon.ui.market.MarketViewModel.CustomRange;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MarketFragment extends Fragment {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final DecimalFormat SIGNED_PCT = buildFormat("+0.00'%';-0.00'%'");
    private static final MathContext PCT_MC = new MathContext(6, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final DateTimeFormatter HEADER_TS_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss");
    private static final DateTimeFormatter X_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter X_DATE_FMT = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter CHIP_DATE_FMT = DateTimeFormatter
            .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault());

    private FragmentMarketBinding binding;
    private MarketViewModel viewModel;
    private AssetSuggestionAdapter suggestionAdapter;

    /** Chip-id → asset map, rebuilt each time held-stock list changes. */
    private final Map<Integer, AssetEntity> chipIdToAsset = new HashMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMarketBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this, MarketViewModel.factory(requireContext()))
                .get(MarketViewModel.class);

        configureChart();
        wireSearch();
        wirePeriodChips();
        binding.refreshButton.setOnClickListener(v -> viewModel.refetch());

        viewModel.heldStocks().observe(getViewLifecycleOwner(), this::renderHeldStocks);
        viewModel.suggestions().observe(getViewLifecycleOwner(), this::renderSuggestions);
        viewModel.pickedSymbol().observe(getViewLifecycleOwner(), s -> updateContentVisibility());
        viewModel.series().observe(getViewLifecycleOwner(), s -> {
            renderHeader(s);
            renderChart(s);
            updateContentVisibility();
        });
        viewModel.error().observe(getViewLifecycleOwner(), this::renderError);
        viewModel.customRange().observe(getViewLifecycleOwner(), this::renderCustomChipText);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ─── Held-stock chips ─────────────────────────────────────────────────

    private void renderHeldStocks(@Nullable List<AssetEntity> stocks) {
        binding.heldStocksChips.removeAllViews();
        chipIdToAsset.clear();
        if (stocks == null || stocks.isEmpty()) {
            binding.heldStocksScroll.setVisibility(View.GONE);
            return;
        }
        binding.heldStocksScroll.setVisibility(View.VISIBLE);

        for (AssetEntity a : stocks) {
            Chip chip = new Chip(requireContext());
            chip.setText(a.ticker);
            chip.setCheckable(true);
            // No explicit chipBackgroundColor — let Widget.FinMon.Chip.Filter (applied
            // via the theme's chipStyle attr) drive idle/checked states. Forcing a
            // transparent background here would break the editorial ink-fill on
            // selected.
            int id = View.generateViewId();
            chip.setId(id);
            chipIdToAsset.put(id, a);
            chip.setOnClickListener(v -> {
                viewModel.pickHeldStock(a);
                // Single-select inside the chip group is built-in; nothing else to do.
            });
            binding.heldStocksChips.addView(chip);
        }
    }

    // ─── Search / autocomplete ────────────────────────────────────────────

    private void wireSearch() {
        suggestionAdapter = new AssetSuggestionAdapter(
                requireContext(), android.R.layout.simple_list_item_1, new ArrayList<>());
        binding.searchField.setAdapter(suggestionAdapter);
        binding.searchField.setThreshold(1);

        binding.searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.search(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        binding.searchField.setOnItemClickListener((AdapterView<?> parent, View v, int position, long id) -> {
            AssetSuggestion picked = suggestionAdapter.getItem(position);
            if (picked == null) return;
            viewModel.pickSuggestion(picked);
            // Reset the search text + clear chip-row selection so the next interaction starts clean.
            binding.searchField.setText("");
            binding.heldStocksChips.clearCheck();
        });
    }

    private void renderSuggestions(@Nullable List<AssetSuggestion> list) {
        suggestionAdapter.clear();
        if (list != null) suggestionAdapter.addAll(list);
        suggestionAdapter.notifyDataSetChanged();
    }

    // ─── Period chips ─────────────────────────────────────────────────────

    private void wirePeriodChips() {
        binding.periodChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.marketPeriodCustom) {
                openDateRangePicker();
                return;
            }
            MarketPeriod period = periodForChip(id);
            if (period != null) viewModel.setPeriod(period);
        });
    }

    @Nullable
    private MarketPeriod periodForChip(int id) {
        if (id == R.id.marketPeriod1d) return MarketPeriod.ONE_DAY;
        if (id == R.id.marketPeriod5d) return MarketPeriod.FIVE_DAYS;
        if (id == R.id.marketPeriod1m) return MarketPeriod.ONE_MONTH;
        if (id == R.id.marketPeriod6m) return MarketPeriod.SIX_MONTHS;
        if (id == R.id.marketPeriodYtd) return MarketPeriod.YTD;
        if (id == R.id.marketPeriod1y) return MarketPeriod.ONE_YEAR;
        if (id == R.id.marketPeriod5y) return MarketPeriod.FIVE_YEARS;
        if (id == R.id.marketPeriodAll) return MarketPeriod.ALL_TIME;
        return null;
    }

    private int chipIdForPeriod(@NonNull MarketPeriod p) {
        switch (p) {
            case ONE_DAY: return R.id.marketPeriod1d;
            case FIVE_DAYS: return R.id.marketPeriod5d;
            case ONE_MONTH: return R.id.marketPeriod1m;
            case SIX_MONTHS: return R.id.marketPeriod6m;
            case YTD: return R.id.marketPeriodYtd;
            case ONE_YEAR: return R.id.marketPeriod1y;
            case FIVE_YEARS: return R.id.marketPeriod5y;
            case ALL_TIME: return R.id.marketPeriodAll;
            case CUSTOM: return R.id.marketPeriodCustom;
            default: return 0;
        }
    }

    private void openDateRangePicker() {
        MaterialDatePicker.Builder<androidx.core.util.Pair<Long, Long>> builder =
                MaterialDatePicker.Builder.dateRangePicker()
                        .setTheme(R.style.ThemeOverlay_FinMon_DatePicker)
                        .setTitleText(R.string.chart_custom_picker_title);

        CustomRange existing = viewModel.customRange().getValue();
        if (existing != null) {
            builder.setSelection(new androidx.core.util.Pair<>(
                    epochUtcMillis(existing.from), epochUtcMillis(existing.to)));
        }

        MaterialDatePicker<androidx.core.util.Pair<Long, Long>> picker = builder.build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null || selection.first == null || selection.second == null) return;
            LocalDate from = utcMillisToLocalDate(selection.first);
            LocalDate to = utcMillisToLocalDate(selection.second);
            viewModel.setCustomRange(from, to);
        });
        picker.addOnNegativeButtonClickListener(v -> reselectActivePeriodChip());
        picker.addOnCancelListener(d -> reselectActivePeriodChip());
        picker.show(getChildFragmentManager(), "market_date_range");
    }

    private void reselectActivePeriodChip() {
        MarketPeriod active = viewModel.selectedPeriod().getValue();
        if (active == null) {
            binding.marketPeriod1d.setChecked(true);
            return;
        }
        if (active == MarketPeriod.CUSTOM && viewModel.customRange().getValue() == null) {
            binding.marketPeriod1d.setChecked(true);
            return;
        }
        if (active == MarketPeriod.CUSTOM) return;
        int id = chipIdForPeriod(active);
        if (id != 0) binding.periodChips.check(id);
    }

    private void renderCustomChipText(@Nullable CustomRange range) {
        if (binding == null) return;
        if (range == null) {
            binding.marketPeriodCustom.setText(R.string.market_period_custom);
        } else {
            binding.marketPeriodCustom.setText(getString(
                    R.string.chart_custom_range_format,
                    range.from.format(CHIP_DATE_FMT),
                    range.to.format(CHIP_DATE_FMT)));
        }
    }

    // ─── Header ───────────────────────────────────────────────────────────

    private void renderHeader(@Nullable MarketSeries s) {
        if (binding == null) return;
        SeriesPoint last = MarketViewModel.lastPoint(s);
        SeriesPoint first = MarketViewModel.firstPoint(s);
        if (s == null || last == null || first == null) {
            binding.headerCard.setVisibility(View.GONE);
            return;
        }

        binding.headerCard.setVisibility(View.VISIBLE);

        binding.headerTicker.setText(s.symbol);
        String name = (s.name != null && !s.name.isEmpty()) ? s.name : "";
        binding.headerName.setText(name);
        binding.headerName.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);

        String ccy = s.currency != null ? s.currency : "";
        renderHeadlineSplit(last.close, ccy);

        BigDecimal delta = last.close.subtract(first.close);
        BigDecimal pct = first.close.signum() != 0
                ? delta.divide(first.close.abs(), PCT_MC).multiply(HUNDRED)
                : BigDecimal.ZERO;

        String arrow;
        int color;
        if (delta.signum() > 0) {
            arrow = getString(R.string.market_arrow_up);
            color = R.color.pnl_positive;
        } else if (delta.signum() < 0) {
            arrow = getString(R.string.market_arrow_down);
            color = R.color.pnl_negative;
        } else {
            arrow = getString(R.string.market_arrow_flat);
            color = R.color.pnl_neutral;
        }

        String pctStr = SIGNED_PCT.format(pct);
        // SIGNED_PCT carries +/- prefix; swap it for the arrow glyph so the
        // headline reads "▲ 12.32%" rather than "▲ +12.32%".
        String pctNoSign = pctStr.replaceFirst("^[+-]", "");
        binding.headerPctDelta.setText(arrow + " " + pctNoSign);
        binding.headerPctDelta.setTextColor(ContextCompat.getColor(requireContext(), color));

        // Bottom line: signed money delta + period tag, colored to match.
        String periodTag = periodTag();
        binding.headerSignedDelta.setText(
                SIGNED_MONEY.format(delta) + (periodTag.isEmpty() ? "" : " · " + periodTag));
        binding.headerSignedDelta.setTextColor(ContextCompat.getColor(requireContext(), color));

        // Timestamp: "now" in the device's local zone — represents when the data was
        // fetched, not the bar's market time. For a real-time browser this is the
        // "you're seeing the world as of N seconds ago" stamp.
        LocalDateTime now = LocalDateTime.now();
        String tsText = now.format(HEADER_TS_FMT);
        binding.headerMeta.setText(getString(R.string.market_header_meta, tsText, ccy));
    }

    /**
     * Render a price into the editorial split-headline form: Display.Headline serif
     * integer, Display.Small ink-soft fraction (incl. leading dot), Inter-caps
     * currency code on the side.
     */
    private void renderHeadlineSplit(@NonNull BigDecimal price, @NonNull String ccy) {
        String formatted = MONEY.format(price);
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
        binding.headerPriceInteger.setText(intPart);
        binding.headerPriceFraction.setText(fracPart);
        binding.headerPriceCurrency.setText(ccy);
        binding.headerPriceCurrency.setVisibility(ccy.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @NonNull
    private String periodTag() {
        MarketPeriod p = viewModel.selectedPeriod().getValue();
        if (p == null) return "";
        switch (p) {
            case ONE_DAY: return getString(R.string.market_period_1d);
            case FIVE_DAYS: return getString(R.string.market_period_5d);
            case ONE_MONTH: return getString(R.string.market_period_1m);
            case SIX_MONTHS: return getString(R.string.market_period_6m);
            case YTD: return getString(R.string.market_period_ytd);
            case ONE_YEAR: return getString(R.string.market_period_1y);
            case FIVE_YEARS: return getString(R.string.market_period_5y);
            case ALL_TIME: return getString(R.string.market_period_all);
            case CUSTOM: return getString(R.string.market_period_custom);
            default: return "";
        }
    }

    // ─── Chart ────────────────────────────────────────────────────────────

    private void configureChart() {
        MpAndroidEditorial.applyLineChart(binding.chart);
        binding.chart.setPinchZoom(true);
        binding.chart.setDragEnabled(true);
        binding.chart.setScaleEnabled(true);

        // Mirror the Y-axis on the right edge — common trading-platform convention,
        // makes the latest price level easy to read from either side of the chart.
        // applyLineChart() disables the right axis by default for Charts pages where
        // we don't want it; Market re-enables and styles it to match the left.
        com.github.mikephil.charting.components.YAxis right = binding.chart.getAxisRight();
        right.setEnabled(true);
        right.setDrawAxisLine(false);
        right.setDrawGridLines(false);
        right.setTextColor(ContextCompat.getColor(requireContext(), R.color.fm_ink_mute));
        right.setTextSize(10f);
        android.graphics.Typeface mono = androidx.core.content.res.ResourcesCompat
                .getFont(requireContext(), R.font.jetbrains_mono);
        if (mono != null) right.setTypeface(mono);
    }

    private void renderChart(@Nullable MarketSeries s) {
        if (binding == null) return;
        if (s == null || s.points.isEmpty()) {
            binding.chart.clear();
            binding.chart.invalidate();
            return;
        }

        boolean intraday = isIntraday();
        long t0 = s.points.get(0).epochSecond;

        List<Entry> entries = new ArrayList<>(s.points.size());
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (SeriesPoint p : s.points) {
            // x-axis units: minutes since first point for intraday, days for daily+.
            float x = intraday
                    ? (p.epochSecond - t0) / 60f
                    : (p.epochSecond - t0) / 86400f;
            float v = p.close.floatValue();
            entries.add(new Entry(x, v));
            if (v < minY) minY = v;
            if (v > maxY) maxY = v;
        }

        float range = maxY - minY;
        float pad = range > 0 ? range * 0.10f : Math.max(0.5f, Math.abs(maxY) * 0.02f);
        binding.chart.getAxisLeft().setAxisMinimum(minY - pad);
        binding.chart.getAxisLeft().setAxisMaximum(maxY + pad);
        // Mirror the same range on the right axis so left/right labels stay aligned.
        binding.chart.getAxisRight().setAxisMinimum(minY - pad);
        binding.chart.getAxisRight().setAxisMaximum(maxY + pad);

        int color = ContextCompat.getColor(requireContext(), R.color.fm_accent);
        LineDataSet set = new LineDataSet(entries, "");
        set.setColor(color);
        set.setLineWidth(1.6f);
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setMode(LineDataSet.Mode.LINEAR);
        // Editorial fill — soft teal area below the price line. ~8 % alpha matches
        // the JSX accent fill on the Value chart.
        set.setDrawFilled(true);
        set.setFillColor(color);
        set.setFillAlpha(20);

        final boolean isIntraday = intraday;
        final long anchorT = t0;
        binding.chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                long secondsFromAnchor = isIntraday
                        ? (long) (value * 60f)
                        : (long) (value * 86400f);
                LocalDateTime dt = Instant.ofEpochSecond(anchorT + secondsFromAnchor)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime();
                return isIntraday ? dt.format(X_TIME_FMT) : dt.format(X_DATE_FMT);
            }
        });

        List<ILineDataSet> sets = new ArrayList<>();
        sets.add(set);
        binding.chart.setData(new LineData(sets));
        binding.chart.notifyDataSetChanged();
        binding.chart.invalidate();
    }

    private boolean isIntraday() {
        MarketPeriod p = viewModel.selectedPeriod().getValue();
        return p == MarketPeriod.ONE_DAY || p == MarketPeriod.FIVE_DAYS;
    }

    private void renderError(@Nullable String msg) {
        if (msg == null) return;
        if (binding == null) return;
        Snackbar.make(binding.getRoot(),
                getString(R.string.market_fetch_failed, msg),
                Snackbar.LENGTH_LONG).show();
    }

    // ─── Visibility / empty state ─────────────────────────────────────────

    private void updateContentVisibility() {
        if (binding == null) return;
        boolean hasPick = viewModel.pickedSymbol().getValue() != null;
        boolean hasData = viewModel.series().getValue() != null
                && !viewModel.series().getValue().points.isEmpty();

        binding.headerCard.setVisibility(hasPick && hasData ? View.VISIBLE : View.GONE);
        binding.periodChipsScroll.setVisibility(hasPick ? View.VISIBLE : View.GONE);
        binding.chart.setVisibility(hasPick && hasData ? View.VISIBLE : View.GONE);
        binding.emptyState.setVisibility(hasPick ? View.GONE : View.VISIBLE);

        if (hasPick && !hasData && Boolean.TRUE.equals(viewModel.loading().getValue())) {
            // Loading state — keep chart hidden but suppress empty-state copy too.
            binding.emptyState.setVisibility(View.GONE);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static LocalDate utcMillisToLocalDate(long utcMillis) {
        return Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static long epochUtcMillis(@NonNull LocalDate d) {
        return d.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli();
    }

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }
}
