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

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.PercentFormatter;
import com.my.finmon.R;
import com.my.finmon.data.repository.PortfolioRepository.AnalyticsBreakdown;
import com.my.finmon.data.repository.PortfolioRepository.Slice;
import com.my.finmon.databinding.FragmentAllocationBinding;
import com.my.finmon.databinding.ItemAllocationLegendBinding;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Charts → Allocation page. Three pie views over the user's holdings as of the
 * filter's window-end, switchable via the chip group: by type, by currency, or
 * by individual asset. Below the pie, an editorial legend list (color swatch ·
 * label · pct · value) replaces MPAndroidChart's built-in legend so the row
 * styling matches Portfolio / Bonds.
 */
public class AllocationPageFragment extends Fragment {

    private enum Filter { BY_TYPE, BY_CURRENCY, BY_ASSET }

    /** Editorial allocation palette. Only 6 colors — when the legend exceeds
     *  this length (very wide By-Asset breakdowns), wrap. */
    private static final int[] PIE_PALETTE_RES = new int[] {
            R.color.fm_alloc_1, R.color.fm_alloc_2, R.color.fm_alloc_3,
            R.color.fm_alloc_4, R.color.fm_alloc_5, R.color.fm_alloc_6,
    };

    private static final DecimalFormat PCT = buildFormat("0.0'%'");
    private static final DecimalFormat WHOLE = buildFormat("#,##0");

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        return new DecimalFormat(pattern, sym);
    }

    private FragmentAllocationBinding binding;
    private AllocationViewModel viewModel;
    @NonNull private Filter activeFilter = Filter.BY_TYPE;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAllocationBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        GlobalFilterViewModel globalFilter = new ViewModelProvider(
                requireActivity(), GlobalFilterViewModel.factory(requireContext()))
                .get(GlobalFilterViewModel.class);
        viewModel = new ViewModelProvider(
                this, AllocationViewModel.factory(requireContext(), globalFilter))
                .get(AllocationViewModel.class);

        configurePie();

        binding.allocationFilterChips.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.allocationByCurrency) activeFilter = Filter.BY_CURRENCY;
            else if (id == R.id.allocationByAsset) activeFilter = Filter.BY_ASSET;
            else activeFilter = Filter.BY_TYPE;
            render(viewModel.data().getValue());
        });

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

    private void configurePie() {
        MpAndroidEditorial.applyPieChart(binding.allocationPie);
        binding.allocationPie.setUsePercentValues(true);
        binding.allocationPie.setDrawEntryLabels(true);
        // Built-in legend is hidden — we render our own editorial legend below.
        binding.allocationPie.getLegend().setEnabled(false);
    }

    private void render(@Nullable AnalyticsBreakdown breakdown) {
        if (binding == null) return;

        if (breakdown == null) {
            binding.allocationLabel.setText(getString(
                    R.string.charts_allocation_kicker_format,
                    filterLabel(activeFilter)));
            binding.allocationSubtitle.setText("");
            binding.allocationEmpty.setVisibility(View.VISIBLE);
            binding.allocationPie.setVisibility(View.GONE);
            binding.allocationFxGapHint.setVisibility(View.GONE);
            binding.allocationLegend.removeAllViews();
            return;
        }

        binding.allocationLabel.setText(getString(
                R.string.charts_allocation_kicker_format,
                filterLabel(activeFilter)));
        binding.allocationSubtitle.setText(getString(
                R.string.analytics_subtitle, breakdown.displayCurrency.name()));
        binding.allocationFxGapHint.setVisibility(breakdown.hasFxGaps ? View.VISIBLE : View.GONE);

        List<Slice> slices = pickSlices(breakdown);
        if (slices.isEmpty()) {
            binding.allocationEmpty.setVisibility(View.VISIBLE);
            binding.allocationPie.setVisibility(View.GONE);
            binding.allocationPie.clear();
            binding.allocationLegend.removeAllViews();
            return;
        }
        binding.allocationEmpty.setVisibility(View.GONE);
        binding.allocationPie.setVisibility(View.VISIBLE);

        int[] palette = resolvePalette();

        // ---- Pie ----
        List<PieEntry> entries = new ArrayList<>(slices.size());
        for (Slice s : slices) {
            // Drop labels in By Asset mode — too crowded inside slices; the legend
            // below identifies each one. Keep them for By Type / By Currency where
            // there are at most a handful of slices.
            String label = activeFilter == Filter.BY_ASSET ? "" : s.label;
            entries.add(new PieEntry(s.value.floatValue(), label));
        }
        PieDataSet set = new PieDataSet(entries, "");
        set.setColors(palette);
        set.setSliceSpace(2f);
        set.setYValuePosition(PieDataSet.ValuePosition.INSIDE_SLICE);

        PieData data = new PieData(set);
        data.setValueFormatter(new PercentFormatter(binding.allocationPie));
        data.setValueTextSize(11f);
        data.setValueTextColor(ContextCompat.getColor(requireContext(), R.color.fm_bg));

        binding.allocationPie.setData(data);
        binding.allocationPie.highlightValues(null);
        binding.allocationPie.animateY(400, Easing.EaseInOutQuad);
        binding.allocationPie.invalidate();

        // ---- Legend ----
        renderLegend(slices, palette);
    }

    private void renderLegend(@NonNull List<Slice> slices, @NonNull int[] palette) {
        binding.allocationLegend.removeAllViews();
        BigDecimal total = BigDecimal.ZERO;
        for (Slice s : slices) total = total.add(s.value);
        if (total.signum() == 0) return;

        for (int i = 0; i < slices.size(); i++) {
            Slice s = slices.get(i);
            ItemAllocationLegendBinding row = ItemAllocationLegendBinding.inflate(
                    LayoutInflater.from(requireContext()), binding.allocationLegend, false);

            row.legendSwatch.setBackgroundColor(palette[i % palette.length]);
            row.legendLabel.setText(s.label);
            // Sub-label currently absent from Slice — keep the kicker hidden until
            // the data carries kind/currency annotations. Showing nothing avoids a
            // dangling Inter-caps line below the label.
            row.legendSubLabel.setVisibility(View.GONE);

            BigDecimal pct = s.value.multiply(new BigDecimal("100"))
                    .divide(total, java.math.MathContext.DECIMAL64);
            row.legendPct.setText(PCT.format(pct));
            row.legendValue.setText(WHOLE.format(s.value));

            binding.allocationLegend.addView(row.getRoot());
        }
    }

    @NonNull
    private int[] resolvePalette() {
        int[] palette = new int[PIE_PALETTE_RES.length];
        for (int i = 0; i < PIE_PALETTE_RES.length; i++) {
            palette[i] = ContextCompat.getColor(requireContext(), PIE_PALETTE_RES[i]);
        }
        return palette;
    }

    @NonNull
    private List<Slice> pickSlices(@NonNull AnalyticsBreakdown b) {
        switch (activeFilter) {
            case BY_CURRENCY: return b.byCurrency;
            case BY_ASSET: return b.byAsset;
            case BY_TYPE:
            default: return b.byType;
        }
    }

    @NonNull
    private String filterLabel(@NonNull Filter f) {
        switch (f) {
            case BY_CURRENCY: return getString(R.string.analytics_filter_by_currency);
            case BY_ASSET: return getString(R.string.analytics_filter_by_asset);
            case BY_TYPE:
            default: return getString(R.string.analytics_filter_by_type);
        }
    }
}
