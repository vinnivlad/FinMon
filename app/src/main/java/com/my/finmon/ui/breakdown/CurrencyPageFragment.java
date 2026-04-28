package com.my.finmon.ui.breakdown;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.NativeBucket;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.databinding.FragmentCurrencyPageBinding;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One ViewPager2 page — shows per-lot trade rows for one currency, under a bucket card
 * (value / invested / P&amp;L) styled to match the main portfolio totals card. Reads
 * period state from the parent {@link CurrencyBreakdownViewModel}.
 */
public class CurrencyPageFragment extends Fragment {

    private static final String ARG_CURRENCY = "currency";

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final DecimalFormat PCT = buildFormat("+0.0'%';-0.0'%'");
    private static final MathContext PCT_MC = new MathContext(4, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final DateTimeFormatter X_LABEL_FMT = DateTimeFormatter.ofPattern("MMM d");

    private FragmentCurrencyPageBinding binding;
    private CurrencyBreakdownViewModel parentVm;
    private CurrencyPageViewModel pageVm;
    private TradeRowAdapter adapter;
    private Currency currency;

    public static CurrencyPageFragment newInstance(@NonNull Currency currency) {
        CurrencyPageFragment f = new CurrencyPageFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CURRENCY, currency.name());
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = requireArguments();
        currency = Currency.valueOf(args.getString(ARG_CURRENCY));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCurrencyPageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new TradeRowAdapter(requireContext());
        binding.rowList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rowList.setAdapter(adapter);

        configureChart();

        parentVm = new ViewModelProvider(requireParentFragment())
                .get(CurrencyBreakdownViewModel.class);

        pageVm = new ViewModelProvider(this, CurrencyPageViewModel.factory(requireContext(), currency))
                .get(CurrencyPageViewModel.class);

        parentVm.period().observe(getViewLifecycleOwner(), p -> {
            if (p != null) pageVm.reload(p);
        });
        parentVm.totals().observe(getViewLifecycleOwner(), this::renderBucket);
        pageVm.rows().observe(getViewLifecycleOwner(), list -> {
            adapter.submitList(list);
            binding.pageEmpty.setVisibility(
                    (list == null || list.isEmpty()) ? View.VISIBLE : View.GONE);
        });
        pageVm.chart().observe(getViewLifecycleOwner(), this::renderChart);
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

    private void renderChart(@Nullable CurrencyPageViewModel.ChartData cd) {
        if (binding == null) return;
        boolean empty = (cd == null || cd.points.isEmpty());
        if (empty) {
            binding.chart.clear();
            binding.chart.invalidate();
            return;
        }

        List<Entry> valueEntries = new ArrayList<>(cd.points.size());
        List<Entry> investedEntries = new ArrayList<>(cd.points.size());
        final LocalDate x0 = cd.points.get(0).date;

        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (CurrencyPageViewModel.Point p : cd.points) {
            float x = p.date.toEpochDay() - x0.toEpochDay();
            float v = p.value.floatValue();
            float i = p.invested.floatValue();
            valueEntries.add(new Entry(x, v));
            investedEntries.add(new Entry(x, i));
            if (v < minY) minY = v;
            if (i < minY) minY = i;
            if (v > maxY) maxY = v;
            if (i > maxY) maxY = i;
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
        valueSet.setDrawCircles(false);
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void renderBucket(@Nullable PortfolioTotals t) {
        if (t == null || binding == null) return;
        NativeBucket nb = t.bucketByCurrency.get(currency);
        if (nb == null) {
            binding.bucketValue.setText("");
            binding.bucketInvested.setText("");
            binding.bucketPnl.setText("");
            binding.bucketDividends.setText("");
            binding.bucketRealized.setText("");
            binding.bucketUnrealized.setText("");
            return;
        }
        String ccy = currency.name();

        binding.bucketValue.setText(MONEY.format(nb.value) + " " + ccy);
        binding.bucketInvested.setText(getString(
                R.string.totals_invested_label,
                MONEY.format(nb.invested) + " " + ccy));

        String pnlText;
        if (nb.invested.signum() != 0) {
            BigDecimal pct = nb.pnl.divide(nb.invested.abs(), PCT_MC).multiply(HUNDRED);
            pnlText = SIGNED_MONEY.format(nb.pnl) + " " + ccy + " (" + PCT.format(pct) + ")";
        } else {
            pnlText = SIGNED_MONEY.format(nb.pnl) + " " + ccy;
        }
        binding.bucketPnl.setText(pnlText);
        binding.bucketPnl.setTextColor(colorFor(nb.pnl.signum()));

        bindBreakdownRow(binding.bucketDividends, nb.dividends, ccy);
        bindBreakdownRow(binding.bucketRealized, nb.realizedPnl, ccy);
        bindBreakdownRow(binding.bucketUnrealized, nb.unrealizedPnl, ccy);
    }

    private void bindBreakdownRow(@NonNull android.widget.TextView v, @NonNull BigDecimal amount, @NonNull String ccy) {
        v.setText(SIGNED_MONEY.format(amount) + " " + ccy);
        v.setTextColor(colorFor(amount.signum()));
    }

    private int colorFor(int sign) {
        int resId = sign > 0
                ? R.color.pnl_positive
                : (sign < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        return ContextCompat.getColor(requireContext(), resId);
    }

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }
}
