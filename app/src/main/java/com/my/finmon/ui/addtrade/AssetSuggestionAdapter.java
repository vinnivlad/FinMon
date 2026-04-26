package com.my.finmon.ui.addtrade;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Adapter for the trade-form's autocomplete dropdown. The default {@link ArrayAdapter}
 * does prefix-matching against {@code item.toString()}, which fights the ViewModel-driven
 * search flow: typing "AAPL" would hide the local VOO/SXR8 rows but the Yahoo results
 * would also be filtered out by the same prefix logic. Passthrough filter — return the
 * adapter's current contents unchanged — lets the ViewModel decide what shows.
 */
public final class AssetSuggestionAdapter extends ArrayAdapter<AssetSuggestion> {

    public AssetSuggestionAdapter(@NonNull Context ctx, int layout, @NonNull List<AssetSuggestion> items) {
        super(ctx, layout, items);
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return PASSTHROUGH;
    }

    private final Filter PASSTHROUGH = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            // Bypass the built-in filter — the adapter's contents are authoritative.
            FilterResults r = new FilterResults();
            r.values = null;
            r.count = getCount();
            return r;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            // Nothing to publish — addAll/clear on the adapter already drove
            // notifyDataSetChanged from the caller side.
        }
    };
}
