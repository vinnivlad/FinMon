package com.my.finmon.ui.portfolio;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.my.finmon.R;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.repository.PortfolioRepository.Holding;
import com.my.finmon.data.repository.PortfolioRepository.MaturedBond;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

/**
 * Three-row-type adapter:
 * <ul>
 *   <li>{@link Item.Active} — a regular open holding (existing layout).</li>
 *   <li>{@link Item.MaturedHeader} — collapsible section header with chevron + count.
 *       Clicking it fires {@code onToggle} so the fragment can flip the expanded flag
 *       and re-submit the list.</li>
 *   <li>{@link Item.Matured} — a redeemed-bond row (only emitted when expanded).</li>
 * </ul>
 *
 * The fragment is responsible for assembling the input list in the right order:
 * <pre>active... + headerIfAnyMatured + maturedRowsIfExpanded</pre>
 */
public final class HoldingsAdapter extends ListAdapter<HoldingsAdapter.Item, RecyclerView.ViewHolder> {

    private static final int VT_ACTIVE = 0;
    private static final int VT_MATURED_HEADER = 1;
    private static final int VT_MATURED_ROW = 2;

    private static final DecimalFormat QTY = buildFormat("#,##0.######");
    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat PCT = buildFormat("+0.0'%';-0.0'%'");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final MathContext PCT_MC = new MathContext(4, RoundingMode.HALF_UP);

    @Nullable private Runnable onToggleMatured;

    public HoldingsAdapter() {
        super(DIFF);
    }

    public void setOnToggleMaturedListener(@Nullable Runnable listener) {
        this.onToggleMatured = listener;
    }

    @Override
    public int getItemViewType(int position) {
        Item it = getItem(position);
        if (it instanceof Item.Active) return VT_ACTIVE;
        if (it instanceof Item.MaturedHeader) return VT_MATURED_HEADER;
        if (it instanceof Item.Matured) return VT_MATURED_ROW;
        throw new IllegalStateException("Unknown item type at " + position);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VT_MATURED_HEADER:
                return new HeaderRow(inflater.inflate(R.layout.item_matured_header, parent, false));
            case VT_MATURED_ROW:
                return new MaturedRow(inflater.inflate(R.layout.item_matured_bond, parent, false));
            case VT_ACTIVE:
            default:
                return new ActiveRow(inflater.inflate(R.layout.item_holding, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item it = getItem(position);
        if (holder instanceof ActiveRow && it instanceof Item.Active) {
            ((ActiveRow) holder).bind(((Item.Active) it).holding);
        } else if (holder instanceof HeaderRow && it instanceof Item.MaturedHeader) {
            ((HeaderRow) holder).bind((Item.MaturedHeader) it);
        } else if (holder instanceof MaturedRow && it instanceof Item.Matured) {
            ((MaturedRow) holder).bind(((Item.Matured) it).bond);
        }
    }

    // ─── Items ──────────────────────────────────────────────────────────────

    /** Polymorphic input. Use the static factories from the fragment. */
    public abstract static class Item {
        abstract Object diffKey();

        public static final class Active extends Item {
            @NonNull final Holding holding;
            public Active(@NonNull Holding h) { this.holding = h; }
            @Override Object diffKey() { return "A:" + holding.asset.id; }
        }

        public static final class MaturedHeader extends Item {
            final int count;
            final boolean expanded;
            public MaturedHeader(int count, boolean expanded) {
                this.count = count;
                this.expanded = expanded;
            }
            @Override Object diffKey() { return "H"; }
        }

        public static final class Matured extends Item {
            @NonNull final MaturedBond bond;
            public Matured(@NonNull MaturedBond b) { this.bond = b; }
            @Override Object diffKey() { return "M:" + bond.assetId; }
        }
    }

    // ─── ViewHolders ────────────────────────────────────────────────────────

    static final class ActiveRow extends RecyclerView.ViewHolder {
        final TextView ticker;
        final TextView typeCurrency;
        final TextView primaryValue;
        final TextView subValue;
        final TextView pnl;

        ActiveRow(@NonNull View v) {
            super(v);
            ticker = v.findViewById(R.id.ticker);
            typeCurrency = v.findViewById(R.id.typeCurrency);
            primaryValue = v.findViewById(R.id.primaryValue);
            subValue = v.findViewById(R.id.subValue);
            pnl = v.findViewById(R.id.pnl);
        }

        void bind(@NonNull Holding h) {
            AssetEntity a = h.asset;
            ticker.setText(a.ticker);
            typeCurrency.setText(a.type.name() + " · " + a.currency.name());
            String ccy = a.currency.name();

            if (a.type == AssetType.CASH) {
                primaryValue.setText(MONEY.format(h.quantity) + " " + ccy);
                subValue.setVisibility(View.GONE);
                pnl.setVisibility(View.GONE);
                return;
            }

            if (h.marketValue != null) {
                primaryValue.setText(MONEY.format(h.marketValue) + " " + ccy);
            } else {
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
                int color = pnlColor(delta);
                pnl.setTextColor(ContextCompat.getColor(itemView.getContext(), color));
                pnl.setVisibility(View.VISIBLE);
            } else {
                pnl.setVisibility(View.GONE);
            }
        }
    }

    final class HeaderRow extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView chevron;

        HeaderRow(@NonNull View v) {
            super(v);
            label = v.findViewById(R.id.headerLabel);
            chevron = v.findViewById(R.id.chevron);
            v.setOnClickListener(view -> {
                if (onToggleMatured != null) onToggleMatured.run();
            });
        }

        void bind(@NonNull Item.MaturedHeader h) {
            label.setText(itemView.getContext().getString(R.string.matured_section_label, h.count));
            chevron.setText(itemView.getContext().getString(
                    h.expanded ? R.string.matured_chevron_expanded : R.string.matured_chevron_collapsed));
        }
    }

    static final class MaturedRow extends RecyclerView.ViewHolder {
        final TextView ticker;
        final TextView maturityLine;
        final TextView pnl;
        final TextView principalLine;

        MaturedRow(@NonNull View v) {
            super(v);
            ticker = v.findViewById(R.id.ticker);
            maturityLine = v.findViewById(R.id.maturityLine);
            pnl = v.findViewById(R.id.pnl);
            principalLine = v.findViewById(R.id.principalLine);
        }

        void bind(@NonNull MaturedBond b) {
            String title = (b.name != null && !b.name.isBlank())
                    ? itemView.getContext().getString(
                            R.string.matured_row_title_with_name, b.ticker, b.name, b.currency.name())
                    : itemView.getContext().getString(
                            R.string.matured_row_title, b.ticker, b.currency.name());
            ticker.setText(title);

            maturityLine.setText(b.maturityDate != null
                    ? itemView.getContext().getString(R.string.matured_row_matured_on, b.maturityDate)
                    : itemView.getContext().getString(R.string.matured_row_matured_unknown));

            pnl.setText(SIGNED_MONEY.format(b.realizedPnl) + " " + b.currency.name());
            pnl.setTextColor(ContextCompat.getColor(itemView.getContext(), pnlColor(b.realizedPnl)));

            principalLine.setText(itemView.getContext().getString(
                    R.string.matured_row_breakdown,
                    MONEY.format(b.principalReturned),
                    MONEY.format(b.couponsReceived),
                    MONEY.format(b.invested)));
        }
    }

    private static int pnlColor(@NonNull BigDecimal delta) {
        return delta.signum() > 0
                ? R.color.pnl_positive
                : (delta.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
    }

    // ─── Diff ───────────────────────────────────────────────────────────────

    private static final DiffUtil.ItemCallback<Item> DIFF = new DiffUtil.ItemCallback<Item>() {
        @Override
        public boolean areItemsTheSame(@NonNull Item a, @NonNull Item b) {
            return Objects.equals(a.diffKey(), b.diffKey());
        }

        @Override
        public boolean areContentsTheSame(@NonNull Item a, @NonNull Item b) {
            if (a instanceof Item.Active && b instanceof Item.Active) {
                Holding ha = ((Item.Active) a).holding;
                Holding hb = ((Item.Active) b).holding;
                return sameBD(ha.quantity, hb.quantity)
                        && sameBD(ha.openCostBasis, hb.openCostBasis)
                        && sameBD(ha.marketValue, hb.marketValue)
                        && Objects.equals(ha.asset.ticker, hb.asset.ticker)
                        && ha.asset.type == hb.asset.type
                        && ha.asset.currency == hb.asset.currency;
            }
            if (a instanceof Item.MaturedHeader && b instanceof Item.MaturedHeader) {
                Item.MaturedHeader ah = (Item.MaturedHeader) a;
                Item.MaturedHeader bh = (Item.MaturedHeader) b;
                return ah.count == bh.count && ah.expanded == bh.expanded;
            }
            if (a instanceof Item.Matured && b instanceof Item.Matured) {
                MaturedBond ba = ((Item.Matured) a).bond;
                MaturedBond bb = ((Item.Matured) b).bond;
                return sameBD(ba.realizedPnl, bb.realizedPnl)
                        && sameBD(ba.invested, bb.invested)
                        && sameBD(ba.couponsReceived, bb.couponsReceived)
                        && sameBD(ba.principalReturned, bb.principalReturned)
                        && Objects.equals(ba.maturityDate, bb.maturityDate)
                        && Objects.equals(ba.ticker, bb.ticker)
                        && Objects.equals(ba.name, bb.name)
                        && ba.currency == bb.currency;
            }
            return false;
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
