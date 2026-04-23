package com.my.finmon.ui.addasset;

import android.app.DatePickerDialog;
import android.os.Bundle;
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

import java.math.BigDecimal;
import java.time.LocalDate;

public class AddAssetFragment extends Fragment {

    private FragmentAddAssetBinding binding;
    private AddAssetViewModel vm;

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

        binding.saveButton.setOnClickListener(v -> onSaveClicked());

        vm = new ViewModelProvider(this, AddAssetViewModel.factory(requireContext()))
                .get(AddAssetViewModel.class);

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

    private void onSaveClicked() {
        clearFieldErrors();

        String ticker = textOf(binding.ticker);
        String stooqTicker = textOf(binding.stooqTicker);
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
        proto.stooqTicker = stooqTicker.isEmpty() ? null : stooqTicker;

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
        binding.stooqTickerLayout.setError(null);
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
        super.onDestroyView();
        binding = null;
    }
}
