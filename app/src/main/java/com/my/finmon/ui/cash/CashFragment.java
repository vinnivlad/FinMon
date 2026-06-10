package com.my.finmon.ui.cash;

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
import com.my.finmon.data.model.Currency;
import com.my.finmon.databinding.FragmentCashBinding;

import java.math.BigDecimal;

/**
 * Cash deposit / withdrawal form. Reached from the Portfolio "+" chooser. Direction
 * chips pick deposit vs withdrawal; the currency dropdown and amount field feed
 * {@link CashViewModel#submit}, which stamps the event at the current instant.
 */
public final class CashFragment extends Fragment {

    private FragmentCashBinding binding;
    private CashViewModel vm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCashBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupCurrencyDropdown();
        binding.saveButton.setOnClickListener(v -> onSaveClicked());

        vm = new ViewModelProvider(this, CashViewModel.factory(requireContext()))
                .get(CashViewModel.class);

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

    private void setupCurrencyDropdown() {
        Currency[] values = Currency.values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i].name();
        binding.currency.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_list_item_1, labels));
    }

    private void onSaveClicked() {
        binding.currencyLayout.setError(null);
        binding.amountLayout.setError(null);

        String currencyStr = textOf(binding.currency);
        String amountStr = textOf(binding.amount);

        boolean ok = true;
        if (currencyStr.isEmpty()) {
            binding.currencyLayout.setError(getString(R.string.error_required));
            ok = false;
        }
        if (amountStr.isEmpty()) {
            binding.amountLayout.setError(getString(R.string.error_required));
            ok = false;
        }
        if (!ok) return;

        Currency currency;
        try {
            currency = Currency.valueOf(currencyStr);
        } catch (IllegalArgumentException e) {
            binding.currencyLayout.setError(getString(R.string.error_required));
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            Snackbar.make(binding.getRoot(),
                    getString(R.string.error_invalid_number), Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (amount.signum() <= 0) {
            binding.amountLayout.setError(getString(R.string.cash_amount_positive));
            return;
        }

        boolean deposit = binding.directionChips.getCheckedChipId() == R.id.directionChipDeposit;
        vm.submit(currency, amount, deposit);
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
