package com.my.finmon.ui.addtrade;

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
import com.my.finmon.data.repository.PortfolioRepository.Side;
import com.my.finmon.databinding.FragmentAddTradeBinding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AddTradeFragment extends Fragment {

    private FragmentAddTradeBinding binding;
    private AddTradeViewModel vm;

    private final Map<String, AssetEntity> assetByLabel = new HashMap<>();
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

        binding.saveButton.setOnClickListener(v -> onSaveClicked());

        vm = new ViewModelProvider(this, AddTradeViewModel.factory(requireContext()))
                .get(AddTradeViewModel.class);

        vm.assets().observe(getViewLifecycleOwner(), this::onAssetsLoaded);

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

    private void onAssetsLoaded(@Nullable List<AssetEntity> list) {
        assetByLabel.clear();
        boolean empty = (list == null || list.isEmpty());
        binding.emptyAssetsHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.saveButton.setEnabled(!empty);

        if (empty) return;

        List<String> labels = new ArrayList<>(list.size());
        for (AssetEntity a : list) {
            String label = a.ticker + " · " + a.currency.name() + " · " + a.type.name();
            labels.add(label);
            assetByLabel.put(label, a);
        }
        binding.asset.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                labels));
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

        AssetEntity asset = assetByLabel.get(assetStr);
        if (asset == null) {
            binding.assetLayout.setError(getString(R.string.error_required));
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

        vm.save(side, asset, qty, price, ts);
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
        binding = null;
    }
}
