package com.my.finmon.ui.breakdown;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.my.finmon.R;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class CurrencyBucketAdapter extends ListAdapter<CurrencyBucket, CurrencyBucketAdapter.Row> {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final DecimalFormat PCT = buildFormat("+0.0'%';-0.0'%'");
    private static final MathContext PCT_MC = new MathContext(4, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public CurrencyBucketAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_currency_bucket, parent, false);
        return new Row(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Row row, int position) {
        row.bind(getItem(position));
    }

    static final class Row extends RecyclerView.ViewHolder {
        final TextView currencyCode;
        final TextView value;
        final TextView invested;
        final TextView pnl;

        Row(@NonNull View itemView) {
            super(itemView);
            currencyCode = itemView.findViewById(R.id.currencyCode);
            value = itemView.findViewById(R.id.value);
            invested = itemView.findViewById(R.id.invested);
            pnl = itemView.findViewById(R.id.pnl);
        }

        void bind(@NonNull CurrencyBucket b) {
            String ccy = b.currency.name();
            currencyCode.setText(ccy);
            value.setText(MONEY.format(b.value) + " " + ccy);
            invested.setText(itemView.getContext().getString(
                    R.string.totals_invested_label, MONEY.format(b.invested) + " " + ccy));

            if (b.invested.signum() != 0) {
                BigDecimal pct = b.pnl.divide(b.invested.abs(), PCT_MC).multiply(HUNDRED);
                pnl.setText(SIGNED_MONEY.format(b.pnl) + " " + ccy + " (" + PCT.format(pct) + ")");
            } else {
                pnl.setText(SIGNED_MONEY.format(b.pnl) + " " + ccy);
            }

            int color = b.pnl.signum() > 0
                    ? R.color.pnl_positive
                    : (b.pnl.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
            pnl.setTextColor(ContextCompat.getColor(itemView.getContext(), color));
        }
    }

    private static final DiffUtil.ItemCallback<CurrencyBucket> DIFF = new DiffUtil.ItemCallback<CurrencyBucket>() {
        @Override
        public boolean areItemsTheSame(@NonNull CurrencyBucket a, @NonNull CurrencyBucket b) {
            return a.currency == b.currency;
        }

        @Override
        public boolean areContentsTheSame(@NonNull CurrencyBucket a, @NonNull CurrencyBucket b) {
            return sameBD(a.value, b.value) && sameBD(a.invested, b.invested) && sameBD(a.pnl, b.pnl);
        }

        private boolean sameBD(BigDecimal x, BigDecimal y) {
            if (x == null || y == null) return x == y;
            return x.compareTo(y) == 0;
        }
    };

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }
}
