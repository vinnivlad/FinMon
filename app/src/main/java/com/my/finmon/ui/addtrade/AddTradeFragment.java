package com.my.finmon.ui.addtrade;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.my.finmon.R;
import com.my.finmon.data.repository.PortfolioRepository.Side;
import com.my.finmon.databinding.FragmentAddTradeBinding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class AddTradeFragment extends Fragment {

    /** Idle window before each keystroke triggers a Yahoo search. */
    private static final long SEARCH_DEBOUNCE_MS = 300L;

    private FragmentAddTradeBinding binding;
    private AddTradeViewModel vm;

    private AssetSuggestionAdapter assetAdapter;
    @Nullable private AssetSuggestion pickedSuggestion;
    /** Flag set while we programmatically rewrite the asset field after a pick, so the
     *  TextWatcher doesn't react and clear {@link #pickedSuggestion}. */
    private boolean suppressAssetTextWatcher;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable pendingSearch;

    @Nullable private LocalDate selectedDate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAddTradeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupSideDropdown();
        setupDatePicker();
        setupAssetAutocomplete();

        binding.saveButton.setOnClickListener(v -> onSaveClicked());

        vm = new ViewModelProvider(this, AddTradeViewModel.factory(requireContext()))
                .get(AddTradeViewModel.class);

        vm.suggestions().observe(getViewLifecycleOwner(), this::onSuggestionsLoaded);

        // The empty-assets hint is now a soft signal — the user can still type a Yahoo
        // ticker to add a new one — so save stays enabled regardless.
        vm.hasLocalAssets().observe(getViewLifecycleOwner(), has ->
                binding.emptyAssetsHint.setVisibility(Boolean.TRUE.equals(has) ? View.GONE : View.VISIBLE));

        vm.saved().observe(getViewLifecycleOwner(), ok -> {
            if (Boolean.TRUE.equals(ok)) {
                NavHostFragment.findNavController(this).navigateUp();
            }
        });

        vm.error().observe(getViewLifecycleOwner(), msg -> {
            if (msg != null) {
                Snackbar.make(binding.getRoot(), msg, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void setupSideDropdown() {
        String[] sides = { Side.BUY.name(), Side.SELL.name() };
        binding.side.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                sides));
    }

    private void setupDatePicker() {
        binding.tradeDate.setOnClickListener(v -> {
            LocalDate seed = (selectedDate != null) ? selectedDate : LocalDate.now();
            DatePickerDialog dlg = new DatePickerDialog(
                    requireContext(),
                    (datePicker, year, month, dayOfMonth) -> {
                        selectedDate = LocalDate.of(year, month + 1, dayOfMonth);
                        binding.tradeDate.setText(selectedDate.toString());
                    },
                    seed.getYear(),
                    seed.getMonthValue() - 1,
                    seed.getDayOfMonth());
            dlg.show();
        });
    }

    private void setupAssetAutocomplete() {
        // Passthrough-filter adapter; the ViewModel decides what shows, not AutoComplete's
        // built-in prefix matcher.
        assetAdapter = new AssetSuggestionAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                new ArrayList<>());
        binding.asset.setAdapter(assetAdapter);
        binding.asset.setThreshold(0);  // dropdown shows at any text length, including empty

        // Show the dropdown as soon as the field is focused or tapped — matches the
        // "tap to browse" expectation users have for combobox-shaped controls.
        binding.asset.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && assetAdapter.getCount() > 0) binding.asset.showDropDown();
        });
        binding.asset.setOnClickListener(v -> {
            if (assetAdapter.getCount() > 0) binding.asset.showDropDown();
        });

        binding.asset.setOnItemClickListener((parent, v, position, id) -> {
            AssetSuggestion picked = assetAdapter.getItem(position);
            if (picked == null) return;
            // Show only the ticker in the field after selection — the full label is busy.
            // Suppress TextWatcher: it would otherwise null out pickedSuggestion right back.
            suppressAssetTextWatcher = true;
            binding.asset.setText(picked.ticker, /* filter */ false);
            binding.asset.setSelection(picked.ticker.length());
            suppressAssetTextWatcher = false;
            pickedSuggestion = picked;
            binding.assetLayout.setError(null);
        });

        binding.asset.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressAssetTextWatcher) return;
                // Typing invalidates any prior selection — user has to re-pick.
                pickedSuggestion = null;
            }
            @Override public void afterTextChanged(Editable s) {
                if (suppressAssetTextWatcher) return;
                scheduleSearch(s.toString());
            }
        });
    }

    private void scheduleSearch(@NonNull String query) {
        if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
        pendingSearch = () -> {
            pendingSearch = null;
            if (vm != null) vm.search(query);
        };
        mainHandler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private void onSuggestionsLoaded(@Nullable List<AssetSuggestion> list) {
        assetAdapter.clear();
        if (list != null) assetAdapter.addAll(list);
        assetAdapter.notifyDataSetChanged();
        // Re-show the dropdown after refresh — newly arrived Yahoo results are useless
        // if the popup is collapsed and the user has to tap again to see them.
        if (binding.asset.isFocused() && assetAdapter.getCount() > 0) {
            binding.asset.showDropDown();
        }
    }

    private void onSaveClicked() {
        clearFieldErrors();

        String sideStr = textOf(binding.side);
        String assetStr = textOf(binding.asset);
        String qtyStr = textOf(binding.quantity);
        String priceStr = textOf(binding.price);

        boolean ok = true;
        if (sideStr.isEmpty()) { binding.sideLayout.setError(getString(R.string.error_required)); ok = false; }
        if (assetStr.isEmpty()) { binding.assetLayout.setError(getString(R.string.error_required)); ok = false; }
        if (qtyStr.isEmpty()) { binding.quantityLayout.setError(getString(R.string.error_required)); ok = false; }
        if (priceStr.isEmpty()) { binding.priceLayout.setError(getString(R.string.error_required)); ok = false; }
        if (selectedDate == null) {
            binding.tradeDateLayout.setError(getString(R.string.error_required)); ok = false;
        }
        if (!ok) return;

        AssetSuggestion sel = pickedSuggestion;
        if (sel == null || !sel.ticker.equalsIgnoreCase(assetStr)) {
            binding.assetLayout.setError(getString(R.string.field_asset_pick_required));
            return;
        }

        BigDecimal qty, price;
        try {
            qty = new BigDecimal(qtyStr);
            price = new BigDecimal(priceStr);
        } catch (NumberFormatException e) {
            Snackbar.make(binding.getRoot(), getString(R.string.error_invalid_number), Snackbar.LENGTH_SHORT).show();
            return;
        }

        // Noon local keeps same-day trades ordered by insertion id when timestamps collide.
        LocalDateTime ts = LocalDateTime.of(selectedDate, LocalTime.NOON);
        Side side = Side.valueOf(sideStr);

        vm.save(side, sel, qty, price, ts);
    }

    private void clearFieldErrors() {
        binding.sideLayout.setError(null);
        binding.assetLayout.setError(null);
        binding.quantityLayout.setError(null);
        binding.priceLayout.setError(null);
        binding.tradeDateLayout.setError(null);
    }

    private static String textOf(@NonNull android.widget.EditText v) {
        return v.getText() == null ? "" : v.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (pendingSearch != null) {
            mainHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        binding = null;
    }
}
