package com.my.finmon.ui.eventlog;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.my.finmon.R;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.repository.PortfolioRepository.EventLogItem;
import com.my.finmon.databinding.ItemEventLogHeaderBinding;
import com.my.finmon.databinding.ItemEventLogRowBinding;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Two-view-type adapter for the Event Log: date headers and event rows. The host
 * fragment builds {@link Item}s from the repo's {@link EventLogItem} list — one
 * header per unique date, then the day's rows newest-first.
 */
final class EventLogAdapter extends ListAdapter<EventLogAdapter.Item, RecyclerView.ViewHolder> {

    private static final int VT_HEADER = 0;
    private static final int VT_ROW = 1;

    /** Locale-aware "Wednesday · 6 May 2026" — matches the masthead's date kicker. */
    private static final DateTimeFormatter HEADER_FMT =
            DateTimeFormatter.ofPattern("EEEE · d MMMM y", Locale.getDefault());

    private static final DecimalFormat MONEY = format("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = format("+#,##0.00;-#,##0.00");

    private static DecimalFormat format(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        return new DecimalFormat(pattern, sym);
    }

    EventLogAdapter() {
        super(DIFF);
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position) instanceof Item.Header ? VT_HEADER : VT_ROW;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VT_HEADER) {
            return new HeaderVH(ItemEventLogHeaderBinding.inflate(inflater, parent, false));
        }
        return new RowVH(ItemEventLogRowBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item it = getItem(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).bind((Item.Header) it);
        } else {
            ((RowVH) holder).bind((Item.Row) it);
        }
    }

    // ── ViewHolders ──────────────────────────────────────────────────────────

    static final class HeaderVH extends RecyclerView.ViewHolder {
        private final ItemEventLogHeaderBinding binding;

        HeaderVH(@NonNull ItemEventLogHeaderBinding b) {
            super(b.getRoot());
            this.binding = b;
        }

        void bind(@NonNull Item.Header h) {
            binding.dateHeader.setText(h.date.format(HEADER_FMT).toUpperCase(Locale.getDefault()));
        }
    }

    static final class RowVH extends RecyclerView.ViewHolder {
        private final ItemEventLogRowBinding binding;

        RowVH(@NonNull ItemEventLogRowBinding b) {
            super(b.getRoot());
            this.binding = b;
        }

        void bind(@NonNull Item.Row r) {
            EventLogItem item = r.event;
            AssetEntity asset = item.primaryAsset;

            binding.eventTitle.setText(formatTitle(itemView.getResources(), item));

            BigDecimal signedAmount = signedAmount(item);
            if (signedAmount == null) {
                // Splits don't have a monetary amount — hide the right column entirely
                // so the row's right edge doesn't carry a stray currency code.
                binding.eventAmount.setVisibility(android.view.View.GONE);
                binding.eventCurrency.setVisibility(android.view.View.GONE);
            } else {
                binding.eventAmount.setVisibility(android.view.View.VISIBLE);
                binding.eventCurrency.setVisibility(android.view.View.VISIBLE);
                binding.eventAmount.setText(SIGNED_MONEY.format(signedAmount));
                int colorRes;
                if (signedAmount.signum() == 0) {
                    colorRes = R.color.pnl_neutral;
                } else if (signedAmount.signum() > 0) {
                    colorRes = R.color.pnl_positive;
                } else {
                    colorRes = R.color.pnl_negative;
                }
                binding.eventAmount.setTextColor(
                        ContextCompat.getColor(itemView.getContext(), colorRes));
                binding.eventCurrency.setText(asset.currency.name());
            }

            String subtitle = formatSubtitle(itemView.getResources(), item);
            binding.eventSubtitle.setText(subtitle != null ? subtitle : "");

            String detail = formatDetail(itemView.getResources(), item);
            if (detail != null) {
                binding.eventDetail.setText(detail);
                binding.eventDetail.setVisibility(android.view.View.VISIBLE);
            } else {
                binding.eventDetail.setVisibility(android.view.View.GONE);
            }
        }

        @NonNull
        private static String formatTitle(
                @NonNull android.content.res.Resources res,
                @NonNull EventLogItem item) {
            switch (item.kind) {
                case BUY:
                    return res.getString(R.string.event_kind_buy, item.primaryAsset.ticker);
                case SELL:
                    return res.getString(R.string.event_kind_sell, item.primaryAsset.ticker);
                case DEPOSIT:
                    return res.getString(R.string.event_kind_deposit);
                case WITHDRAWAL:
                    return res.getString(R.string.event_kind_withdrawal);
                case DIVIDEND: {
                    String src = item.partnerAsset != null
                            ? item.partnerAsset.ticker : "—";
                    return res.getString(R.string.event_kind_dividend, src);
                }
                case COUPON: {
                    String src = item.partnerAsset != null
                            ? item.partnerAsset.ticker : "—";
                    return res.getString(R.string.event_kind_coupon, src);
                }
                case MATURITY: {
                    String src = item.partnerAsset != null
                            ? item.partnerAsset.ticker : "—";
                    return res.getString(R.string.event_kind_maturity, src);
                }
                case CONVERSION: {
                    String to = item.conversionTargetCurrency != null
                            ? item.conversionTargetCurrency.name() : "—";
                    return res.getString(R.string.event_kind_conversion,
                            item.primaryAsset.currency.name(), to);
                }
                case SPLIT:
                    return res.getString(R.string.event_kind_split, item.primaryAsset.ticker);
            }
            return "";
        }

        @Nullable
        private static String formatSubtitle(
                @NonNull android.content.res.Resources res,
                @NonNull EventLogItem item) {
            switch (item.kind) {
                case BUY:
                case SELL:
                    return res.getString(R.string.event_sub_trade,
                            item.primaryAsset.type.name(),
                            stripTrailingZeros(item.primary.amount),
                            stripTrailingZeros(item.primary.price));
                case DEPOSIT:
                case WITHDRAWAL:
                    return res.getString(R.string.event_sub_cash,
                            item.primaryAsset.currency.name());
                case SPLIT:
                    return res.getString(R.string.event_sub_split_ratio,
                            stripTrailingZeros(item.primary.amount));
                default:
                    return null;
            }
        }

        @Nullable
        private static String formatDetail(
                @NonNull android.content.res.Resources res,
                @NonNull EventLogItem item) {
            if (item.kind == EventLogItem.Kind.CONVERSION
                    && item.partner != null
                    && item.conversionTargetCurrency != null) {
                return res.getString(R.string.event_sub_conversion_to,
                        MONEY.format(item.partner.amount),
                        item.conversionTargetCurrency.name());
            }
            return null;
        }

        /**
         * Money amount paired with a sign reflecting whether the user's portfolio
         * received or paid this leg, so the row's right-hand value reads at a glance.
         * Returns null for SPLIT (a ratio, not a monetary movement).
         */
        @Nullable
        private static BigDecimal signedAmount(@NonNull EventLogItem item) {
            switch (item.kind) {
                case BUY: {
                    // Outflow on the cash side — display the cash leg's signed amount
                    // when we have it, falling back to amount × price on the asset leg.
                    BigDecimal total = item.partner != null
                            ? item.partner.amount
                            : item.primary.amount.multiply(item.primary.price);
                    return total.negate();
                }
                case SELL: {
                    BigDecimal total = item.partner != null
                            ? item.partner.amount
                            : item.primary.amount.multiply(item.primary.price);
                    return total;
                }
                case DEPOSIT:
                case DIVIDEND:
                case COUPON:
                case MATURITY:
                    return item.primary.amount;
                case WITHDRAWAL:
                    return item.primary.amount.negate();
                case CONVERSION:
                    return item.primary.amount.negate();
                case SPLIT:
                    return null;
            }
            return null;
        }

        @NonNull
        private static String stripTrailingZeros(@NonNull BigDecimal v) {
            BigDecimal stripped = v.stripTrailingZeros();
            // Avoid scientific notation that stripTrailingZeros can yield for whole numbers.
            return stripped.scale() < 0 ? stripped.toPlainString() : stripped.toPlainString();
        }
    }

    // ── Items + DiffUtil ─────────────────────────────────────────────────────

    static abstract class Item {
        static final class Header extends Item {
            @NonNull final LocalDate date;

            Header(@NonNull LocalDate date) { this.date = date; }
        }

        static final class Row extends Item {
            @NonNull final EventLogItem event;

            Row(@NonNull EventLogItem event) { this.event = event; }
        }
    }

    private static final DiffUtil.ItemCallback<Item> DIFF = new DiffUtil.ItemCallback<Item>() {
        @Override
        public boolean areItemsTheSame(@NonNull Item a, @NonNull Item b) {
            if (a instanceof Item.Header && b instanceof Item.Header) {
                return ((Item.Header) a).date.equals(((Item.Header) b).date);
            }
            if (a instanceof Item.Row && b instanceof Item.Row) {
                return ((Item.Row) a).event.primary.id == ((Item.Row) b).event.primary.id;
            }
            return false;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Item a, @NonNull Item b) {
            // Events are append-only and the adapter rebuilds Items from scratch on
            // each refresh, so identity equality is safe enough — diffs detect inserts.
            return areItemsTheSame(a, b);
        }
    };

}
