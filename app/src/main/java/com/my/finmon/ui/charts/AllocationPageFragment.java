package com.my.finmon.ui.charts;

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
import com.my.finmon.databinding.FragmentAllocationBinding;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Charts → Allocation page. Three pie views over the user's holdings as of the
 * filter's window-end, switchable via the chip group: by type, by currency, or
 * by individual asset.
 */
public class AllocationPageFragment extends Fragment {

    private enum Filter { BY_TYPE, BY_CURRENCY, BY_ASSET }

    /**
     * Combined palette — five built-in templates concatenated for ~20 visually distinct
     * colors. With the default {@code MATERIAL_COLORS} alone (4 colors), the By Asset
     * pie repeats colors after the fourth slice; this widens the runway considerably.
     */
    private static final int[] PIE_PALETTE = concat(
            ColorTemplate.MATERIAL_COLORS,
            ColorTemplate.COLORFUL_COLORS,
            ColorTemplate.JOYFUL_COLORS,
            ColorTemplate.LIBERTY_COLORS,
            ColorTemplate.PASTEL_COLORS);

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
        binding.allocationPie.setUsePercentValues(true);
        binding.allocationPie.getDescription().setEnabled(false);
        binding.allocationPie.setNoDataText("");
        binding.allocationPie.setExtraOffsets(8f, 8f, 8f, 8f);
        binding.allocationPie.setDrawHoleEnabled(true);
        binding.allocationPie.setHoleColor(android.graphics.Color.TRANSPARENT);
        binding.allocationPie.setTransparentCircleAlpha(0);
        binding.allocationPie.setHoleRadius(40f);
        binding.allocationPie.setRotationEnabled(true);
        binding.allocationPie.setDrawEntryLabels(true);
        binding.allocationPie.setEntryLabelTextSize(11f);
        binding.allocationPie.setEntryLabelColor(android.graphics.Color.WHITE);

        Legend legend = binding.allocationPie.getLegend();
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
        legend.setWordWrapEnabled(true);
    }

    private void render(@Nullable AnalyticsBreakdown breakdown) {
        if (binding == null) return;

        if (breakdown == null) {
            binding.allocationSubtitle.setText("");
            binding.allocationEmpty.setVisibility(View.VISIBLE);
            binding.allocationPie.setVisibility(View.GONE);
            binding.allocationFxGapHint.setVisibility(View.GONE);
            return;
        }

        binding.allocationSubtitle.setText(getString(
                R.string.analytics_subtitle, breakdown.displayCurrency.name()));
        binding.allocationFxGapHint.setVisibility(breakdown.hasFxGaps ? View.VISIBLE : View.GONE);

        List<Slice> slices = pickSlices(breakdown);
        if (slices.isEmpty()) {
            binding.allocationEmpty.setVisibility(View.VISIBLE);
            binding.allocationPie.setVisibility(View.GONE);
            binding.allocationPie.clear();
            return;
        }
        binding.allocationEmpty.setVisibility(View.GONE);
        binding.allocationPie.setVisibility(View.VISIBLE);

        List<PieEntry> entries = new ArrayList<>(slices.size());
        for (Slice s : slices) entries.add(new PieEntry(s.value.floatValue(), s.label));

        PieDataSet set = new PieDataSet(entries, "");
        set.setColors(PIE_PALETTE);
        set.setSliceSpace(2f);
        set.setYValuePosition(PieDataSet.ValuePosition.INSIDE_SLICE);

        PieData data = new PieData(set);
        data.setValueFormatter(new PercentFormatter(binding.allocationPie));
        data.setValueTextSize(12f);
        data.setValueTextColor(android.graphics.Color.WHITE);

        // By Asset can have many slices; legend would balloon and offer little value
        // over the on-slice labels. By Type / By Currency are small — keep legend.
        binding.allocationPie.getLegend().setEnabled(activeFilter != Filter.BY_ASSET);

        binding.allocationPie.setData(data);
        binding.allocationPie.highlightValues(null);
        binding.allocationPie.animateY(400, Easing.EaseInOutQuad);
        binding.allocationPie.invalidate();
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
