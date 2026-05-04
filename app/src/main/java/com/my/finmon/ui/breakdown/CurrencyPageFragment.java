package com.my.finmon.ui.breakdown;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.my.finmon.R;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.PortfolioRepository.NativeBucket;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.data.repository.PortfolioRepository.TradeRow;
import com.my.finmon.databinding.FragmentCurrencyPageBinding;
import com.my.finmon.ui.filter.FilterPeriod;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

import java.math.BigDecimal;
import java.util.List;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
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

    private FragmentCurrencyPageBinding binding;
    private CurrencyBreakdownViewModel parentVm;
    private GlobalFilterViewModel globalFilter;
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

        adapter = new TradeRowAdapter(requireContext(), currency.name());
        binding.rowList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rowList.setAdapter(adapter);
        // Match Holdings — hairline divider between rows, inset to row content padding.
        DividerItemDecoration divider = new DividerItemDecoration(
                requireContext(), DividerItemDecoration.VERTICAL);
        Drawable hairline = ContextCompat.getDrawable(requireContext(), R.drawable.fm_row_divider);
        if (hairline != null) divider.setDrawable(hairline);
        binding.rowList.addItemDecoration(divider);

        parentVm = new ViewModelProvider(requireParentFragment())
                .get(CurrencyBreakdownViewModel.class);
        globalFilter = new ViewModelProvider(
                requireActivity(), GlobalFilterViewModel.factory(requireContext()))
                .get(GlobalFilterViewModel.class);

        pageVm = new ViewModelProvider(this, CurrencyPageViewModel.factory(requireContext(), currency))
                .get(CurrencyPageViewModel.class);

        // Reload rows on either period change or custom-range change. Both paths go
        // through a single helper that reads the latest values from the global filter,
        // so we can't get stale (period, range) pairs.
        globalFilter.selectedPeriod().observe(getViewLifecycleOwner(), p -> reloadFromFilter());
        globalFilter.customRange().observe(getViewLifecycleOwner(), r -> reloadFromFilter());
        // Card renders from two sources: current value/invested come from parent's
        // all-time totals; windowed P&L breakdown is summed from the rows. Re-render
        // when either updates so the card reacts to filter changes.
        parentVm.totals().observe(getViewLifecycleOwner(), t -> renderBucket());
        pageVm.rows().observe(getViewLifecycleOwner(), list -> {
            adapter.submitList(list);
            binding.pageEmpty.setVisibility(
                    (list == null || list.isEmpty()) ? View.VISIBLE : View.GONE);
            renderBucket();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void reloadFromFilter() {
        FilterPeriod p = globalFilter.selectedPeriod().getValue();
        if (p == null) return;
        pageVm.reload(p, globalFilter.customRange().getValue());
    }

    private void renderBucket() {
        if (binding == null) return;
        String ccy = currency.name();
        binding.bucketKicker.setText(getString(R.string.breakdown_bucket_kicker, ccy));
        binding.bucketValueCurrency.setText(ccy);

        PortfolioTotals t = parentVm.totals().getValue();
        if (t == null) return;
        NativeBucket nb = t.bucketByCurrency.get(currency);
        if (nb == null) {
            binding.bucketValueInteger.setText("");
            binding.bucketValueFraction.setText("");
            binding.bucketInvested.setText("");
            binding.bucketPnl.setText("");
            binding.bucketDividends.setText("");
            binding.bucketRealized.setText("");
            binding.bucketUnrealized.setText("");
            return;
        }

        // Value + Invested are period-independent (current snapshot / lifetime capital).
        renderHeadlineSplit(nb.value);
        binding.bucketInvested.setText(getString(
                R.string.totals_invested_label,
                MONEY.format(nb.invested) + " " + ccy));

        // P&L breakdown comes from the windowed rows (TradeRow already does the
        // period-windowing for realized / unrealized / dividends). Falls back to
        // bucket-level lifetime numbers if rows haven't loaded yet.
        List<TradeRow> rows = pageVm.rows().getValue();
        BigDecimal periodDividends;
        BigDecimal periodRealized;
        BigDecimal periodUnrealized;
        BigDecimal periodPnl;
        if (rows == null) {
            periodDividends = nb.dividends;
            periodRealized = nb.realizedPnl;
            periodUnrealized = nb.unrealizedPnl;
            periodPnl = nb.pnl;
        } else {
            periodDividends = BigDecimal.ZERO;
            periodRealized = BigDecimal.ZERO;
            periodUnrealized = BigDecimal.ZERO;
            periodPnl = BigDecimal.ZERO;
            for (TradeRow r : rows) {
                periodDividends = periodDividends.add(r.windowDividends);
                periodRealized = periodRealized.add(r.windowRealizedPnl);
                periodUnrealized = periodUnrealized.add(r.windowUnrealizedPnl);
                periodPnl = periodPnl.add(r.windowTotalPnl);
            }
        }

        // Period P&L: ▲/▼ glyph + signed amount + (pct%), colored.
        String arrow = periodPnl.signum() > 0
                ? getString(R.string.arrow_up)
                : (periodPnl.signum() < 0 ? getString(R.string.arrow_down) : "");
        StringBuilder pnlText = new StringBuilder();
        if (!arrow.isEmpty()) pnlText.append(arrow).append(' ');
        pnlText.append(SIGNED_MONEY.format(periodPnl));
        if (nb.invested.signum() != 0) {
            BigDecimal pct = periodPnl.divide(nb.invested.abs(), PCT_MC).multiply(HUNDRED);
            pnlText.append(" (").append(PCT.format(pct)).append(')');
        }
        binding.bucketPnl.setText(pnlText.toString());
        binding.bucketPnl.setTextColor(colorFor(periodPnl.signum()));

        bindBreakdownRow(binding.bucketDividends, periodDividends);
        bindBreakdownRow(binding.bucketRealized, periodRealized);
        bindBreakdownRow(binding.bucketUnrealized, periodUnrealized);
    }

    private void renderHeadlineSplit(@NonNull BigDecimal amount) {
        String formatted = MONEY.format(amount);
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
        binding.bucketValueInteger.setText(intPart);
        binding.bucketValueFraction.setText(fracPart);
    }

    private void bindBreakdownRow(@NonNull android.widget.TextView v, @NonNull BigDecimal amount) {
        v.setText(SIGNED_MONEY.format(amount));
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
