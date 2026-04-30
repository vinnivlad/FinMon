package com.my.finmon.ui.manualevent;

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
import com.my.finmon.data.model.EventType;
import com.my.finmon.databinding.FragmentManualEventBinding;
import com.my.finmon.ui.manualevent.ManualEventViewModel.RedemptionPreview;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Backup form for manually recording a dividend, bond coupon, or bond redemption — used
 * when auto-ingest (Yahoo for stocks, NBU for UAH bonds) can't reach the event (off-NBU
 * bonds, special distributions, foreign instruments, very old historical data).
 *
 * <p>Stamps every event at 09:00 local on the picked date. Same-day non-cash trades are
 * stamped at noon ({@link com.my.finmon.ui.addtrade.AddTradeFragment}); the 09:00 offset
 * keeps the timestamp-based trade-leg detection in
 * {@code PortfolioRepository.computeTotalsSync} from misclassifying the income or
 * redemption legs as trade legs.
 */
public final class ManualEventFragment extends Fragment {

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    /** Locale-aware short date for the pay-date field. */
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault());

    private FragmentManualEventBinding binding;
    private ManualEventViewModel vm;

    @Nullable private List<AssetEntity> heldAssets;
    @Nullable private AssetEntity pickedAsset;
    @Nullable private LocalDate selectedDate;
    /** Active form mode. The XML chip group is wired to exactly the three values
     *  this fragment supports — DIVIDEND, MATURITY, SPLIT — and the save dispatch
     *  switches on the same enum directly. */
    @NonNull private EventType eventType = EventType.DIVIDEND;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentManualEventBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize VM first — chip and date listeners route preview requests through it.
        vm = new ViewModelProvider(this, ManualEventViewModel.factory(requireContext()))
                .get(ManualEventViewModel.class);

        setupKindChips();
        setupDatePicker();
        binding.saveButton.setOnClickListener(v -> onSaveClicked());

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

        vm.redemptionPreview().observe(getViewLifecycleOwner(), this::renderRedemptionPreview);

        applyEventTypeToUi();
    }

    private void setupKindChips() {
        binding.kindChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.kindChipMaturity) eventType = EventType.MATURITY;
            else if (id == R.id.kindChipSplit) eventType = EventType.SPLIT;
            else eventType = EventType.DIVIDEND;
            applyEventTypeToUi();
            // Different event types target different asset subsets — bonds-only for
            // maturity, stocks-only for split, stocks+bonds for dividend/coupon. Drop
            // the picked asset if it's no longer eligible so the user can't submit a
            // stale pick.
            if (pickedAsset != null && !isAssetEligibleFor(pickedAsset, eventType)) {
                pickedAsset = null;
                binding.asset.setText("", false);
            }
            renderAssetDropdown();
            requestPreviewIfApplicable();
        });
    }

    private boolean isAssetEligibleFor(@NonNull AssetEntity a, @NonNull EventType t) {
        switch (t) {
            case MATURITY: return a.type == AssetType.BOND;
            case SPLIT:    return a.type == AssetType.STOCK;
            case DIVIDEND:
            default:       return true;  // stocks + bonds
        }
    }

    /**
     * Show/hide the amount, ratio, and redemption-preview blocks based on the
     * selected event type. Each type exposes a different input shape:
     * <ul>
     *   <li>DIVIDEND — amount field visible.</li>
     *   <li>MATURITY — both fields hidden; preview line shows derived cash.</li>
     *   <li>SPLIT — ratio field visible.</li>
     * </ul>
     */
    private void applyEventTypeToUi() {
        boolean dividend = (eventType == EventType.DIVIDEND);
        boolean split = (eventType == EventType.SPLIT);
        boolean maturity = (eventType == EventType.MATURITY);
        binding.amountLayout.setVisibility(dividend ? View.VISIBLE : View.GONE);
        binding.amountHelp.setVisibility(dividend ? View.VISIBLE : View.GONE);
        binding.ratioLayout.setVisibility(split ? View.VISIBLE : View.GONE);
        binding.ratioHelp.setVisibility(split ? View.VISIBLE : View.GONE);
        if (!maturity) {
            binding.redemptionPreview.setVisibility(View.GONE);
        }
    }

    private void renderRedemptionPreview(@Nullable RedemptionPreview p) {
        if (eventType != EventType.MATURITY || p == null) {
            binding.redemptionPreview.setVisibility(View.GONE);
            return;
        }
        if (p.alreadyRedeemed) {
            binding.redemptionPreview.setText(R.string.manual_event_already_redeemed);
            binding.redemptionPreview.setVisibility(View.VISIBLE);
            return;
        }
        if (p.qty.signum() <= 0) {
            binding.redemptionPreview.setText(R.string.manual_event_redemption_no_open);
            binding.redemptionPreview.setVisibility(View.VISIBLE);
            return;
        }
        binding.redemptionPreview.setText(getString(
                R.string.manual_event_redemption_preview,
                MONEY.format(p.cashAmount),
                p.currency.name(),
                MONEY.format(p.face),
                MONEY.format(p.qty)));
        binding.redemptionPreview.setVisibility(View.VISIBLE);
    }

    private void requestPreviewIfApplicable() {
        if (eventType == EventType.MATURITY) {
            LocalDate asOf = (selectedDate != null) ? selectedDate : LocalDate.now();
            vm.requestRedemptionPreview(pickedAsset, asOf);
        }
    }

    private void onAssetsLoaded(@Nullable List<AssetEntity> list) {
        heldAssets = list != null ? list : Collections.emptyList();
        renderAssetDropdown();
    }

    /**
     * Filter {@link #heldAssets} by the current {@link #eventType} and rebuild the dropdown.
     * Called on both initial asset load and chip change.
     */
    private void renderAssetDropdown() {
        if (binding == null) return;
        List<AssetEntity> source = (heldAssets != null) ? heldAssets : Collections.emptyList();

        List<AssetEntity> visible = new java.util.ArrayList<>(source.size());
        for (AssetEntity a : source) {
            if (isAssetEligibleFor(a, eventType)) visible.add(a);
        }

        boolean empty = visible.isEmpty();
        binding.emptyAssetsHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.assetLayout.setEnabled(!empty);

        String[] labels = new String[visible.size()];
        for (int i = 0; i < visible.size(); i++) {
            AssetEntity a = visible.get(i);
            labels[i] = a.ticker + " · " + a.type.name() + " · " + a.currency.name();
        }
        binding.asset.setAdapter(new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                labels));
        binding.asset.setOnItemClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            pickedAsset = visible.get(position);
            binding.asset.setText(labels[position], false);
            binding.assetLayout.setError(null);
            requestPreviewIfApplicable();
        });
    }

    private void setupDatePicker() {
        binding.payDate.setOnClickListener(v -> {
            LocalDate seed = (selectedDate != null) ? selectedDate : LocalDate.now();
            DatePickerDialog dlg = new DatePickerDialog(
                    requireContext(),
                    (datePicker, year, month, dayOfMonth) -> {
                        selectedDate = LocalDate.of(year, month + 1, dayOfMonth);
                        binding.payDate.setText(DATE_FMT.format(selectedDate));
                        binding.payDateLayout.setError(null);
                        requestPreviewIfApplicable();
                    },
                    seed.getYear(),
                    seed.getMonthValue() - 1,
                    seed.getDayOfMonth());
            dlg.show();
        });
    }

    private void onSaveClicked() {
        clearFieldErrors();

        boolean ok = true;
        if (pickedAsset == null) {
            binding.assetLayout.setError(getString(R.string.error_required));
            ok = false;
        }
        if (selectedDate == null) {
            binding.payDateLayout.setError(getString(R.string.error_required));
            ok = false;
        }

        if (eventType == EventType.MATURITY) {
            if (pickedAsset != null && pickedAsset.type != AssetType.BOND) {
                Snackbar.make(binding.getRoot(),
                        getString(R.string.manual_event_redemption_only_bonds),
                        Snackbar.LENGTH_LONG).show();
                ok = false;
            }
            if (!ok) return;
            vm.submitMaturity(pickedAsset, selectedDate);
            return;
        }

        if (eventType == EventType.SPLIT) {
            if (pickedAsset != null && pickedAsset.type != AssetType.STOCK) {
                Snackbar.make(binding.getRoot(),
                        getString(R.string.manual_event_split_only_stocks),
                        Snackbar.LENGTH_LONG).show();
                ok = false;
            }
            String ratioStr = textOf(binding.ratio);
            if (ratioStr.isEmpty()) {
                binding.ratioLayout.setError(getString(R.string.error_required));
                ok = false;
            }
            if (!ok) return;

            BigDecimal ratio;
            try {
                ratio = new BigDecimal(ratioStr);
            } catch (NumberFormatException e) {
                Snackbar.make(binding.getRoot(),
                        getString(R.string.error_invalid_number),
                        Snackbar.LENGTH_SHORT).show();
                return;
            }
            if (ratio.signum() <= 0) {
                binding.ratioLayout.setError(getString(R.string.manual_event_ratio_positive));
                return;
            }
            // 09:00 — same offset coupons / dividends use, dodges the noon-trade-leg
            // detection in computeTotalsSync.
            LocalDateTime ts = LocalDateTime.of(selectedDate, LocalTime.of(9, 0));
            vm.submitSplit(pickedAsset, ratio, ts);
            return;
        }

        // DIVIDEND (income) — amount field is required. Falls through to here when
        // eventType isn't MATURITY or SPLIT.
        String amountStr = textOf(binding.amount);
        if (amountStr.isEmpty()) {
            binding.amountLayout.setError(getString(R.string.error_required));
            ok = false;
        }
        if (!ok) return;

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            Snackbar.make(binding.getRoot(),
                    getString(R.string.error_invalid_number),
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (amount.signum() <= 0) {
            binding.amountLayout.setError(getString(R.string.manual_event_amount_positive));
            return;
        }

        // 09:00 keeps the timestamp distinct from same-day trades (which land at noon),
        // so the trade-leg detection in computeTotalsSync doesn't misclassify this row.
        LocalDateTime ts = LocalDateTime.of(selectedDate, LocalTime.of(9, 0));
        vm.submitIncome(pickedAsset, amount, ts);
    }

    private void clearFieldErrors() {
        binding.assetLayout.setError(null);
        binding.amountLayout.setError(null);
        binding.ratioLayout.setError(null);
        binding.payDateLayout.setError(null);
    }

    private static String textOf(@NonNull android.widget.EditText v) {
        return v.getText() == null ? "" : v.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        return new DecimalFormat(pattern, sym);
    }
}
