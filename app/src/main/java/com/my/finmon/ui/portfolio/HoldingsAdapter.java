package com.my.finmon.ui.portfolio;

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
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.repository.PortfolioRepository.Holding;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

/**
 * Each row:
 *   ┌── ticker ──────────────── primaryValue ──┐
 *   └── type · ccy ─────────────── subValue ──┘
 *                                      pnl (colored)
 *
 * primaryValue: market value in native currency (falls back to quantity if no market
 * data yet, e.g. a just-added stock with no price stored).
 * subValue: qty × avg-cost-per-unit (STOCK/BOND), hidden for cash.
 * pnl: marketValue − openCostBasis, colored green/red/neutral.
 */
public final class HoldingsAdapter extends ListAdapter<Holding, HoldingsAdapter.Row> {

    private static final DecimalFormat QTY = buildFormat("#,##0.######");
    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat PCT = buildFormat("+0.0'%';-0.0'%'");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final MathContext PCT_MC = new MathContext(4, RoundingMode.HALF_UP);

    public HoldingsAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_holding, parent, false);
        return new Row(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Row row, int position) {
        row.bind(getItem(position));
    }

    static final class Row extends RecyclerView.ViewHolder {
        final TextView ticker;
        final TextView typeCurrency;
        final TextView primaryValue;
        final TextView subValue;
        final TextView pnl;

        Row(@NonNull View itemView) {
            super(itemView);
            ticker = itemView.findViewById(R.id.ticker);
            typeCurrency = itemView.findViewById(R.id.typeCurrency);
            primaryValue = itemView.findViewById(R.id.primaryValue);
            subValue = itemView.findViewById(R.id.subValue);
            pnl = itemView.findViewById(R.id.pnl);
        }

        void bind(@NonNull Holding h) {
            AssetEntity a = h.asset;
            ticker.setText(a.ticker);
            typeCurrency.setText(a.type.name() + " · " + a.currency.name());

            String ccy = a.currency.name();

            if (a.type == AssetType.CASH) {
                // Cash: just the balance. No cost, no P&L.
                primaryValue.setText(MONEY.format(h.quantity) + " " + ccy);
                subValue.setVisibility(View.GONE);
                pnl.setVisibility(View.GONE);
                return;
            }

            // STOCK / BOND
            if (h.marketValue != null) {
                primaryValue.setText(MONEY.format(h.marketValue) + " " + ccy);
            } else {
                // No price yet — fall back to showing just the quantity.
                primaryValue.setText(QTY.format(h.quantity));
            }

            if (h.openCostBasis != null) {
                subValue.setText(QTY.format(h.quantity) + " · cost " + MONEY.format(h.openCostBasis));
                subValue.setVisibility(View.VISIBLE);
            } else {
                subValue.setVisibility(View.GONE);
            }

            if (h.marketValue != null && h.openCostBasis != null
                    && h.openCostBasis.signum() != 0) {
                BigDecimal delta = h.marketValue.subtract(h.openCostBasis);
                BigDecimal pct = delta.divide(h.openCostBasis, PCT_MC).multiply(new BigDecimal("100"));
                pnl.setText(SIGNED_MONEY.format(delta) + " (" + PCT.format(pct) + ")");
                int color = delta.signum() > 0
                        ? R.color.pnl_positive
                        : (delta.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
                pnl.setTextColor(ContextCompat.getColor(itemView.getContext(), color));
                pnl.setVisibility(View.VISIBLE);
            } else {
                pnl.setVisibility(View.GONE);
            }
        }
    }

    private static final DiffUtil.ItemCallback<Holding> DIFF = new DiffUtil.ItemCallback<Holding>() {
        @Override
        public boolean areItemsTheSame(@NonNull Holding a, @NonNull Holding b) {
            return a.asset.id == b.asset.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Holding a, @NonNull Holding b) {
            return sameBD(a.quantity, b.quantity)
                    && sameBD(a.openCostBasis, b.openCostBasis)
                    && sameBD(a.marketValue, b.marketValue)
                    && Objects.equals(a.asset.ticker, b.asset.ticker)
                    && a.asset.type == b.asset.type
                    && a.asset.currency == b.asset.currency;
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
