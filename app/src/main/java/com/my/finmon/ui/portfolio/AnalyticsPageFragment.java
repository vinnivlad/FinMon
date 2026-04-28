package com.my.finmon.ui.portfolio;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.PercentFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.my.finmon.R;
import com.my.finmon.data.repository.PortfolioRepository.AnalyticsBreakdown;
import com.my.finmon.data.repository.PortfolioRepository.Slice;
import com.my.finmon.databinding.FragmentAnalyticsPageBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * One ViewPager2 page on the portfolio screen — three pie views over the active
 * holdings, switched via a chip-group filter:
 * <ul>
 *   <li><b>By Type</b> — STOCK / BOND / CASH (default)</li>
 *   <li><b>By Currency</b> — USD / EUR / UAH</li>
 *   <li><b>By Asset</b> — one slice per asset (incl. each cash pile)</li>
 * </ul>
 *
 * <p>All slice values come pre-converted to the user's display currency by
 * {@link com.my.finmon.data.repository.PortfolioRepository#getAnalyticsAsOf}.
 * Slices are pre-sorted largest-first so the legend reads naturally.
 */
public class AnalyticsPageFragment extends Fragment {

    private enum Filter { BY_TYPE, BY_CURRENCY, BY_ASSET }

    /**
     * Combined palette — four built-in templates concatenated for ~20 visually distinct
     * colors. With the default {@code MATERIAL_COLORS} alone (4 colors), the By Asset
     * pie repeats colors after the fourth slice; this widens the runway considerably
     * before duplicates appear.
     */
    private static final int[] PIE_PALETTE = concat(
            ColorTemplate.MATERIAL_COLORS,
            ColorTemplate.COLORFUL_COLORS,
            ColorTemplate.JOYFUL_COLORS,
            ColorTemplate.LIBERTY_COLORS,
            ColorTemplate.PASTEL_COLORS);

    private FragmentAnalyticsPageBinding binding;
    private PortfolioViewModel viewModel;
    @NonNull private Filter activeFilter = Filter.BY_TYPE;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAnalyticsPageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireParentFragment()).get(PortfolioViewModel.class);

        configurePie();

        binding.analyticsFilterChips.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.analyticsByCurrency) activeFilter = Filter.BY_CURRENCY;
            else if (id == R.id.analyticsByAsset) activeFilter = Filter.BY_ASSET;
            else activeFilter = Filter.BY_TYPE;
            render(viewModel.analytics().getValue());
        });

        viewModel.analytics().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void configurePie() {
        binding.analyticsPie.setUsePercentValues(true);
        binding.analyticsPie.getDescription().setEnabled(false);
        binding.analyticsPie.setNoDataText("");
        binding.analyticsPie.setExtraOffsets(8f, 8f, 8f, 8f);
        binding.analyticsPie.setDrawHoleEnabled(true);
        binding.analyticsPie.setHoleColor(android.graphics.Color.TRANSPARENT);
        binding.analyticsPie.setTransparentCircleAlpha(0);
        binding.analyticsPie.setHoleRadius(40f);
        binding.analyticsPie.setRotationEnabled(true);
        // Names drawn inside their slices — pairs with INSIDE_SLICE percentages so
        // both labels stay within the chart bounds regardless of rotation.
        binding.analyticsPie.setDrawEntryLabels(true);
        binding.analyticsPie.setEntryLabelTextSize(11f);
        binding.analyticsPie.setEntryLabelColor(android.graphics.Color.WHITE);

        Legend legend = binding.analyticsPie.getLegend();
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
        legend.setWordWrapEnabled(true);
    }

    private void render(@Nullable AnalyticsBreakdown breakdown) {
        if (binding == null) return;

        if (breakdown == null) {
            binding.analyticsSubtitle.setText("");
            binding.analyticsEmpty.setVisibility(View.VISIBLE);
            binding.analyticsPie.setVisibility(View.GONE);
            binding.analyticsFxGapHint.setVisibility(View.GONE);
            return;
        }

        binding.analyticsSubtitle.setText(getString(
                R.string.analytics_subtitle, breakdown.displayCurrency.name()));
        binding.analyticsFxGapHint.setVisibility(breakdown.hasFxGaps ? View.VISIBLE : View.GONE);

        List<Slice> slices = pickSlices(breakdown);
        if (slices.isEmpty()) {
            binding.analyticsEmpty.setVisibility(View.VISIBLE);
            binding.analyticsPie.setVisibility(View.GONE);
            binding.analyticsPie.clear();
            return;
        }
        binding.analyticsEmpty.setVisibility(View.GONE);
        binding.analyticsPie.setVisibility(View.VISIBLE);

        List<PieEntry> entries = new ArrayList<>(slices.size());
        for (Slice s : slices) entries.add(new PieEntry(s.value.floatValue(), s.label));

        PieDataSet set = new PieDataSet(entries, "");
        set.setColors(PIE_PALETTE);
        set.setSliceSpace(2f);
        // Percentages on the slice itself — guaranteed to stay inside the chart bounds
        // regardless of pie rotation.
        set.setYValuePosition(PieDataSet.ValuePosition.INSIDE_SLICE);

        PieData data = new PieData(set);
        data.setValueFormatter(new PercentFormatter(binding.analyticsPie));
        data.setValueTextSize(12f);
        data.setValueTextColor(android.graphics.Color.WHITE);

        // By Asset can have many slices (one per held position + each cash pile), so
        // the legend would balloon and offer little value over the on-slice labels.
        // For By Type / By Currency the legend is small and useful — keep it.
        binding.analyticsPie.getLegend().setEnabled(activeFilter != Filter.BY_ASSET);

        binding.analyticsPie.setData(data);
        binding.analyticsPie.highlightValues(null);
        binding.analyticsPie.animateY(400, Easing.EaseInOutQuad);
        binding.analyticsPie.invalidate();
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
    private static int[] concat(@NonNull int[]... arrays) {
        int total = 0;
        for (int[] a : arrays) total += a.length;
        int[] out = new int[total];
        int offset = 0;
        for (int[] a : arrays) {
            System.arraycopy(a, 0, out, offset, a.length);
            offset += a.length;
        }
        return out;
    }
}
