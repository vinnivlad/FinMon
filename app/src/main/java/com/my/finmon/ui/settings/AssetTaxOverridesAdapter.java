package com.my.finmon.ui.settings;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.my.finmon.R;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Inline editor for per-asset tax overrides. Each row shows ticker · currency, the
 * applicable default in the subtitle, and an editable percent field. Empty field clears
 * the override; any non-empty value is parsed + clamped to [0, 100] on focus loss.
 */
public final class AssetTaxOverridesAdapter
        extends RecyclerView.Adapter<AssetTaxOverridesAdapter.VH> {

    /** Called with (assetId, override-or-null) on field commit (focus loss). */
    public interface OnRateChanged extends BiConsumer<Long, BigDecimal> {}

    private final List<AssetTaxOverridesViewModel.Row> items = new ArrayList<>();
    private final OnRateChanged onRateChanged;

    public AssetTaxOverridesAdapter(@NonNull OnRateChanged onRateChanged) {
        this.onRateChanged = onRateChanged;
    }

    public void submitList(@NonNull List<AssetTaxOverridesViewModel.Row> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tax_override, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class VH extends RecyclerView.ViewHolder {
        final android.widget.TextView ticker;
        final android.widget.TextView subtitle;
        final TextInputLayout rateLayout;
        final TextInputEditText rateInput;
        @Nullable AssetTaxOverridesViewModel.Row current;
        @Nullable TextWatcher activeWatcher;
        boolean internalEdit;

        VH(@NonNull View itemView) {
            super(itemView);
            this.ticker = itemView.findViewById(R.id.ticker);
            this.subtitle = itemView.findViewById(R.id.subtitle);
            this.rateLayout = itemView.findViewById(R.id.rateLayout);
            this.rateInput = itemView.findViewById(R.id.rateInput);

            // Single focus-change handler shared across binds — reads current row at fire
            // time so rebinds on recycle don't double-register listeners.
            rateInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus || current == null) return;
                String raw = rateInput.getText() == null ? "" : rateInput.getText().toString().trim();
                if (raw.isEmpty()) {
                    if (current.taxRatePct != null) {
                        onRateChanged.accept(current.assetId, null);
                    }
                    rateLayout.setError(null);
                    return;
                }
                try {
                    BigDecimal pct = new BigDecimal(raw);
                    if (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) {
                        rateLayout.setError(itemView.getContext().getString(R.string.settings_tax_invalid));
                        return;
                    }
                    rateLayout.setError(null);
                    if (current.taxRatePct == null || current.taxRatePct.compareTo(pct) != 0) {
                        onRateChanged.accept(current.assetId, pct);
                    }
                } catch (NumberFormatException e) {
                    rateLayout.setError(itemView.getContext().getString(R.string.settings_tax_invalid));
                }
            });
        }

        void bind(@NonNull AssetTaxOverridesViewModel.Row row) {
            current = row;
            ticker.setText(row.ticker + " · " + row.currency);
            String defaultText = row.defaultPct.stripTrailingZeros().toPlainString();
            subtitle.setText(row.type.name() + " · "
                    + itemView.getContext().getString(R.string.tax_overrides_default_hint, defaultText));

            // Detach old watcher before mutating text so we don't re-fire bookkeeping.
            if (activeWatcher != null) rateInput.removeTextChangedListener(activeWatcher);

            internalEdit = true;
            String text = (row.taxRatePct != null)
                    ? row.taxRatePct.stripTrailingZeros().toPlainString()
                    : "";
            rateInput.setText(text);
            internalEdit = false;
            rateLayout.setError(null);

            activeWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    if (internalEdit) return;
                    rateLayout.setError(null);
                }
            };
            rateInput.addTextChangedListener(activeWatcher);
        }
    }
}
