package com.my.finmon.ui.addasset;

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
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.databinding.FragmentAddAssetBinding;
import com.my.finmon.ui.addtrade.AssetSuggestion;
import com.my.finmon.ui.addtrade.AssetSuggestionAdapter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class AddAssetFragment extends Fragment {

    /** Idle window before each keystroke triggers a Yahoo search. */
    private static final long SEARCH_DEBOUNCE_MS = 300L;

    private FragmentAddAssetBinding binding;
    private AddAssetViewModel vm;

    private AssetSuggestionAdapter assetAdapter;
    @Nullable private AssetSuggestion pickedSuggestion;
    private boolean suppressTickerWatcher;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable pendingSearch;

    @Nullable private LocalDate selectedMaturity;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAddAssetBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupCurrencyDropdown();
        setupTypeDropdown();
        setupMaturityPicker();
        setupTickerAutocomplete();

        binding.saveButton.setOnClickListener(v -> onSaveClicked());

        vm = new ViewModelProvider(this, AddAssetViewModel.factory(requireContext()))
                .get(AddAssetViewModel.class);

        vm.suggestions().observe(getViewLifecycleOwner(), this::onSuggestionsLoaded);
        vm.resolvedCurrency().observe(getViewLifecycleOwner(), this::onCurrencyResolved);

        vm.savedAssetId().observe(getViewLifecycleOwner(), id -> {
            // Success — pop back to portfolio; its onResume will refresh.
            NavHostFragment.findNavController(this).navigateUp();
        });

        vm.error().observe(getViewLifecycleOwner(), msg -> {
            if (msg != null) {
                Snackbar.make(binding.getRoot(), msg, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void setupCurrencyDropdown() {
        String[] currencies = new String[Currency.values().length];
        for (int i = 0; i < currencies.length; i++) currencies[i] = Currency.values()[i].name();
        binding.currency.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                currencies));
    }

    private void setupTypeDropdown() {
        // CASH is seeded, not user-created, so the picker offers only STOCK and BOND.
        String[] types = { AssetType.STOCK.name(), AssetType.BOND.name() };
        binding.type.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                types));
        binding.type.setOnItemClickListener((parent, v, position, id) -> {
            boolean isBond = AssetType.BOND.name().equals(types[position]);
            binding.bondFields.setVisibility(isBond ? View.VISIBLE : View.GONE);
        });
    }

    private void setupMaturityPicker() {
        binding.maturity.setOnClickListener(v -> {
            LocalDate seed = (selectedMaturity != null) ? selectedMaturity : LocalDate.now().plusYears(3);
            DatePickerDialog dlg = new DatePickerDialog(
                    requireContext(),
                    (datePicker, year, month, dayOfMonth) -> {
                        selectedMaturity = LocalDate.of(year, month + 1, dayOfMonth);
                        binding.maturity.setText(selectedMaturity.toString());
                    },
                    seed.getYear(),
                    seed.getMonthValue() - 1,
                    seed.getDayOfMonth());
            dlg.show();
        });
    }

    private void setupTickerAutocomplete() {
        assetAdapter = new AssetSuggestionAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                new ArrayList<>());
        binding.ticker.setAdapter(assetAdapter);
        binding.ticker.setThreshold(0);

        binding.ticker.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && assetAdapter.getCount() > 0) binding.ticker.showDropDown();
        });
        binding.ticker.setOnClickListener(v -> {
            if (assetAdapter.getCount() > 0) binding.ticker.showDropDown();
        });

        binding.ticker.setOnItemClickListener((parent, v, position, id) -> {
            AssetSuggestion picked = assetAdapter.getItem(position);
            if (picked == null) return;
            suppressTickerWatcher = true;
            binding.ticker.setText(picked.ticker, /* filter */ false);
            binding.ticker.setSelection(picked.ticker.length());
            suppressTickerWatcher = false;
            applyPick(picked);
        });

        binding.ticker.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressTickerWatcher) return;
                // Free typing invalidates any prior pick.
                pickedSuggestion = null;
            }
            @Override public void afterTextChanged(Editable s) {
                if (suppressTickerWatcher) return;
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

    /**
     * Auto-fills the rest of the form from a search pick. For Yahoo stocks, currency
     * arrives async via {@link #onCurrencyResolved}. For NBU bonds, every field is
     * known up-front including face, yield, and maturity.
     */
    private void applyPick(@NonNull AssetSuggestion picked) {
        pickedSuggestion = picked;
        binding.tickerLayout.setError(null);

        if (picked.remoteTicker != null) {
            binding.remoteTicker.setText(picked.remoteTicker);
        } else {
            binding.remoteTicker.setText("");
        }
        applyType(picked.type);

        if (picked.knownCurrency != null) {
            binding.currency.setText(picked.knownCurrency.name(), /* filter */ false);
        } else if (picked.remoteTicker != null) {
            // Yahoo stock — currency lands later via onCurrencyResolved.
            binding.currency.setText("", false);
            vm.lookupCurrency(picked.remoteTicker);
        }

        // Bond-specific auto-fill from NBU.
        if (picked.source == AssetSuggestion.Source.REMOTE_BOND) {
            if (picked.bondFace != null) {
                binding.face.setText(picked.bondFace.toPlainString());
            }
            if (picked.bondYieldPct != null) {
                binding.yield.setText(picked.bondYieldPct.toPlainString());
            }
            if (picked.bondMaturity != null) {
                selectedMaturity = picked.bondMaturity;
                binding.maturity.setText(picked.bondMaturity.toString());
            }
        }
    }

    private void applyType(@NonNull AssetType type) {
        binding.type.setText(type.name(), /* filter */ false);
        binding.bondFields.setVisibility(type == AssetType.BOND ? View.VISIBLE : View.GONE);
    }

    private void onSuggestionsLoaded(@Nullable List<AssetSuggestion> list) {
        assetAdapter.clear();
        if (list != null) assetAdapter.addAll(list);
        assetAdapter.notifyDataSetChanged();
        if (binding.ticker.isFocused() && assetAdapter.getCount() > 0) {
            binding.ticker.showDropDown();
        }
    }

    private void onCurrencyResolved(@Nullable Currency ccy) {
        if (ccy == null || binding == null) return;
        // Don't clobber whatever the user has manually typed in the meantime.
        if (textOf(binding.currency).isEmpty()) {
            binding.currency.setText(ccy.name(), /* filter */ false);
        }
    }

    private void onSaveClicked() {
        clearFieldErrors();

        String ticker = textOf(binding.ticker);
        String remoteTicker = textOf(binding.remoteTicker);
        String currencyStr = textOf(binding.currency);
        String typeStr = textOf(binding.type);

        boolean ok = true;
        if (ticker.isEmpty()) { binding.tickerLayout.setError(getString(R.string.error_required)); ok = false; }
        if (currencyStr.isEmpty()) { binding.currencyLayout.setError(getString(R.string.error_required)); ok = false; }
        if (typeStr.isEmpty()) { binding.typeLayout.setError(getString(R.string.error_required)); ok = false; }
        if (!ok) return;

        AssetEntity proto = new AssetEntity();
        proto.ticker = ticker;
        proto.currency = Currency.valueOf(currencyStr);
        proto.type = AssetType.valueOf(typeStr);
        proto.remoteTicker = remoteTicker.isEmpty() ? null : remoteTicker;
        // Carry the human-readable name + ISIN from the autocomplete pick (if any).
        // Manual entry leaves both null.
        if (pickedSuggestion != null && pickedSuggestion.ticker.equalsIgnoreCase(ticker)) {
            proto.name = pickedSuggestion.name;
            proto.isin = pickedSuggestion.isin;
        }

        if (proto.type == AssetType.BOND) {
            if (selectedMaturity == null) {
                binding.maturityLayout.setError(getString(R.string.error_required));
                return;
            }
            proto.bondMaturityDate = selectedMaturity;

            String faceStr = textOf(binding.face);
            String yieldStr = textOf(binding.yield);
            if (faceStr.isEmpty()) { binding.faceLayout.setError(getString(R.string.error_required)); return; }
            if (yieldStr.isEmpty()) { binding.yieldLayout.setError(getString(R.string.error_required)); return; }
            try {
                proto.bondInitialPrice = new BigDecimal(faceStr);
                proto.bondYieldPct = new BigDecimal(yieldStr);
            } catch (NumberFormatException e) {
                Snackbar.make(binding.getRoot(), "Invalid numeric value", Snackbar.LENGTH_SHORT).show();
                return;
            }
        }

        vm.save(proto);
    }

    private void clearFieldErrors() {
        binding.tickerLayout.setError(null);
        binding.remoteTickerLayout.setError(null);
        binding.currencyLayout.setError(null);
        binding.typeLayout.setError(null);
        binding.maturityLayout.setError(null);
        binding.faceLayout.setError(null);
        binding.yieldLayout.setError(null);
    }

    private static String textOf(@NonNull android.widget.EditText v) {
        return v.getText() == null ? "" : v.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        if (pendingSearch != null) {
            mainHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        super.onDestroyView();
        binding = null;
    }
}
