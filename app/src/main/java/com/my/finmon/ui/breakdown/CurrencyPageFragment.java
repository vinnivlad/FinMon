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
