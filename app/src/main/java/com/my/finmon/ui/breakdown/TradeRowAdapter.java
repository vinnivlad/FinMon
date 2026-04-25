package com.my.finmon.ui.breakdown;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.my.finmon.R;
import com.my.finmon.data.repository.PortfolioRepository.TradeRow;
import com.my.finmon.databinding.ItemTradeRowBinding;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class TradeRowAdapter extends ListAdapter<TradeRow, TradeRowAdapter.VH> {

    private static final DecimalFormat MONEY = makeFormat("#,##0.##");
    private static final DecimalFormat QTY = makeFormat("#,##0.####");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final int colorPositive;
    private final int colorNegative;
    private final int colorNeutral;

    TradeRowAdapter(@NonNull Context ctx) {
        super(DIFF);
        this.colorPositive = ContextCompat.getColor(ctx, R.color.pnl_positive);
        this.colorNegative = ContextCompat.getColor(ctx, R.color.pnl_negative);
        this.colorNeutral = ContextCompat.getColor(ctx, R.color.pnl_neutral);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTradeRowBinding b = ItemTradeRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        TradeRow r = getItem(position);
        Context ctx = h.itemView.getContext();

        h.b.tickerLine.setText(ctx.getString(
                R.string.trade_row_ticker_line, r.ticker, r.assetType.name()));

        boolean partiallyClosed = r.remainingQty.compareTo(r.originalQty) != 0;
        String qtyLine = partiallyClosed
                ? ctx.getString(R.string.trade_row_qty_line_partial,
                    r.purchasedAt.toLocalDate().format(DATE),
                    QTY.format(r.originalQty),
                    MONEY.format(r.purchasePrice),
                    QTY.format(r.remainingQty))
                : ctx.getString(R.string.trade_row_qty_line_full,
                    r.purchasedAt.toLocalDate().format(DATE),
                    QTY.format(r.originalQty),
                    MONEY.format(r.purchasePrice));
        h.b.qtyLine.setText(qtyLine);

        h.b.breakdownLine.setText(ctx.getString(
                R.string.trade_row_breakdown,
                signed(r.windowRealizedPnl),
                signed(r.windowUnrealizedPnl)));

        h.b.totalPnl.setText(signed(r.windowTotalPnl));
        h.b.totalPnl.setTextColor(colorFor(r.windowTotalPnl));
    }

    private int colorFor(@NonNull BigDecimal v) {
        int s = v.signum();
        if (s > 0) return colorPositive;
        if (s < 0) return colorNegative;
        return colorNeutral;
    }

    private static String signed(@NonNull BigDecimal v) {
        return (v.signum() >= 0 ? "+" : "") + MONEY.format(v);
    }

    private static DecimalFormat makeFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        sym.setGroupingSeparator(',');
        sym.setDecimalSeparator('.');
        return new DecimalFormat(pattern, sym);
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemTradeRowBinding b;
        VH(@NonNull ItemTradeRowBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }

    private static final DiffUtil.ItemCallback<TradeRow> DIFF =
            new DiffUtil.ItemCallback<TradeRow>() {
                @Override
                public boolean areItemsTheSame(@NonNull TradeRow a, @NonNull TradeRow b) {
                    // assetId + timestamp uniquely identifies a lot (no trade_id column by design).
                    return a.assetId == b.assetId && a.purchasedAt.equals(b.purchasedAt);
                }
                @Override
                public boolean areContentsTheSame(@NonNull TradeRow a, @NonNull TradeRow b) {
                    return a.originalQty.compareTo(b.originalQty) == 0
                            && a.remainingQty.compareTo(b.remainingQty) == 0
                            && a.purchasePrice.compareTo(b.purchasePrice) == 0
                            && a.windowRealizedPnl.compareTo(b.windowRealizedPnl) == 0
                            && a.windowUnrealizedPnl.compareTo(b.windowUnrealizedPnl) == 0;
                }
            };
}
