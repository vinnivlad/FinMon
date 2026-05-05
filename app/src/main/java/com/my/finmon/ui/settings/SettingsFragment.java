package com.my.finmon.ui.settings;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupMenu;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.my.finmon.R;
import com.my.finmon.ServiceLocator;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.ImportExportRepository;
import com.my.finmon.databinding.FragmentSettingsBinding;
import com.my.finmon.notifications.NotificationScheduler;
import com.my.finmon.prefs.ThemeMode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;

/**
 * Settings hub. Hosts:
 * <ul>
 *     <li>Display-currency picker — purely a render-layer choice; the app's base currency
 *         stays fixed at USD ({@code PortfolioRepository.BASE_CURRENCY}).</li>
 *     <li>"Add asset" — alternate entry to {@code AddAssetFragment}, since most asset
 *         creation now happens implicitly via the Add Trade autocomplete.</li>
 *     <li>"Record manual event" — backup path for income (dividend/coupon) the auto-ingest
 *         pipeline can't reach (off-NBU bonds, special distributions, historical data).</li>
 *     <li>Export/Import — full-portfolio JSON dump and restore via SAF.</li>
 * </ul>
 */
public final class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";

    private FragmentSettingsBinding binding;
    private SettingsViewModel viewModel;

    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::onExportPicked);
    private final ActivityResultLauncher<Intent> importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::onImportPicked);

    /** Triggered when the user toggles notifications on while POST_NOTIFICATIONS
     *  hasn't been granted yet (Android 13+). On grant we commit the toggle and
     *  schedule the workers; on deny we snap the switch back to off. */
    private final ActivityResultLauncher<String> notifPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            this::onNotificationPermissionResult);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(
                this,
                SettingsViewModel.factory(requireContext())
        ).get(SettingsViewModel.class);

        setupDisplayCurrencyRow();
        setupThemeModeRow();
        setupTaxDefaults();
        setupNotificationsRow();

        binding.buttonAddAsset.setOnClickListener(v ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_settings_to_addAsset));

        binding.buttonRecordManualEvent.setOnClickListener(v ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_settings_to_manualEvent));

        binding.buttonTaxOverrides.setOnClickListener(v ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_settings_to_taxOverrides));

        binding.buttonExportData.setOnClickListener(v -> launchExport());
        binding.buttonImportData.setOnClickListener(v -> launchImport());
    }

    /** Set while the LiveData observer is programmatically syncing the switch from
     *  prefs — keeps the user-action handler from interpreting that as a tap. */
    private boolean syncingNotificationsSwitch = false;

    private void setupNotificationsRow() {
        // Initial position from prefs; observer keeps it in sync if changed elsewhere.
        viewModel.notificationsEnabled().observe(getViewLifecycleOwner(), enabled -> {
            if (binding == null || enabled == null) return;
            if (binding.notificationsSwitch.isChecked() != enabled) {
                syncingNotificationsSwitch = true;
                binding.notificationsSwitch.setChecked(enabled);
                syncingNotificationsSwitch = false;
            }
        });

        // Tapping the row toggles the switch — same behavior as the other Settings rows.
        binding.notificationsRow.setOnClickListener(v ->
                binding.notificationsSwitch.toggle());

        binding.notificationsSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (syncingNotificationsSwitch) return;  // observer-initiated, not user
            if (checked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && ContextCompat.checkSelfPermission(
                                requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    // Commit happens in the permission callback; don't write the pref yet.
                    return;
                }
                commitNotificationsEnabled(true);
            } else {
                commitNotificationsEnabled(false);
            }
        });
    }

    private void onNotificationPermissionResult(boolean granted) {
        if (granted) {
            commitNotificationsEnabled(true);
        } else {
            // Snap the switch back to off — pref was never written, scheduler stays cancelled.
            if (binding != null) binding.notificationsSwitch.setChecked(false);
            if (binding != null) {
                Snackbar.make(binding.getRoot(),
                        getString(R.string.settings_notifications_permission_denied),
                        Snackbar.LENGTH_LONG).show();
            }
        }
    }

    private void commitNotificationsEnabled(boolean enabled) {
        viewModel.setNotificationsEnabled(enabled);
        NotificationScheduler.apply(requireContext().getApplicationContext(), enabled);
    }

    private void setupTaxDefaults() {
        bindTaxField(
                binding.defaultStockTaxInput,
                viewModel.defaultStockTaxPct(),
                viewModel::setDefaultStockTaxPct,
                binding.defaultStockTaxLayout);
        bindTaxField(
                binding.defaultBondTaxInput,
                viewModel.defaultBondTaxPct(),
                viewModel::setDefaultBondTaxPct,
                binding.defaultBondTaxLayout);
    }

    /**
     * Two-way binding for a percent input. Pre-fills from the LiveData; on focus loss,
     * parses + clamps + persists. Keeps the field reactive to external changes (e.g. an
     * import that overwrote settings) without fighting the user mid-typing.
     */
    private void bindTaxField(
            @NonNull EditText input,
            @NonNull androidx.lifecycle.LiveData<BigDecimal> source,
            @NonNull java.util.function.Consumer<BigDecimal> sink,
            @NonNull com.google.android.material.textfield.TextInputLayout layout) {
        final boolean[] internalEdit = { false };
        source.observe(getViewLifecycleOwner(), pct -> {
            if (pct == null) return;
            if (input.isFocused()) return;  // don't stomp on the user's in-progress edit
            String text = pct.stripTrailingZeros().toPlainString();
            if (!text.equals(input.getText() == null ? "" : input.getText().toString())) {
                internalEdit[0] = true;
                input.setText(text);
                internalEdit[0] = false;
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (internalEdit[0]) return;
                layout.setError(null);
            }
        });
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;  // commit on blur
            String raw = input.getText() == null ? "" : input.getText().toString().trim();
            if (raw.isEmpty()) return;
            try {
                BigDecimal pct = new BigDecimal(raw);
                if (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) {
                    layout.setError(getString(R.string.settings_tax_invalid));
                    return;
                }
                sink.accept(pct);
            } catch (NumberFormatException e) {
                layout.setError(getString(R.string.settings_tax_invalid));
            }
        });
    }

    private void setupThemeModeRow() {
        ThemeMode[] options = ThemeMode.values();

        viewModel.themeMode().observe(getViewLifecycleOwner(), mode -> {
            if (mode == null || binding == null) return;
            binding.themeValue.setText(themeModeLabel(mode));
        });

        binding.themeRow.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(requireContext(), binding.themeValue);
            for (int i = 0; i < options.length; i++) {
                popup.getMenu().add(0, i, i, themeModeLabel(options[i]));
            }
            popup.setOnMenuItemClickListener((MenuItem item) -> {
                int idx = item.getItemId();
                if (idx < 0 || idx >= options.length) return false;
                // setThemeMode triggers AppCompatDelegate.setDefaultNightMode which
                // recreates the activity synchronously. Popup is dismissed before
                // recreate by calling .dismiss() — though for PopupMenu the system
                // already collapses it on item-click.
                viewModel.setThemeMode(options[idx]);
                return true;
            });
            popup.show();
        });
    }

    private String themeModeLabel(@NonNull ThemeMode mode) {
        switch (mode) {
            case LIGHT:  return getString(R.string.theme_mode_light);
            case DARK:   return getString(R.string.theme_mode_dark);
            case SYSTEM:
            default:     return getString(R.string.theme_mode_system);
        }
    }

    private void setupDisplayCurrencyRow() {
        Currency[] options = Currency.values();

        viewModel.displayCurrency().observe(getViewLifecycleOwner(), c -> {
            if (c == null || binding == null) return;
            binding.currencyValue.setText(c.name());
        });

        binding.currencyRow.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(requireContext(), binding.currencyValue);
            for (int i = 0; i < options.length; i++) {
                popup.getMenu().add(0, i, i, options[i].name());
            }
            popup.setOnMenuItemClickListener((MenuItem item) -> {
                int idx = item.getItemId();
                if (idx < 0 || idx >= options.length) return false;
                viewModel.setDisplayCurrency(options[idx]);
                return true;
            });
            popup.show();
        });
    }

    // ─── Import / Export ────────────────────────────────────────────────────

    private void launchExport() {
        String fileName = getString(R.string.export_default_filename, LocalDate.now().toString());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, fileName);
        exportLauncher.launch(intent);
    }

    private void launchImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "*/*"});
        importLauncher.launch(intent);
    }

    private void onExportPicked(@NonNull ActivityResult result) {
        Uri uri = (result.getData() != null) ? result.getData().getData() : null;
        if (uri == null) return;
        ServiceLocator sl = ServiceLocator.get(requireContext());
        ImportExportRepository repo = sl.importExportRepository();
        ExecutorService bridge = sl.viewExecutor();
        bridge.execute(() -> {
            try {
                String json = repo.exportToJson().get();
                int assetCount = countMatches(json, "\"ticker\"");
                int eventCount = countMatches(json, "\"timestamp\"");
                writeBytes(uri, json.getBytes(StandardCharsets.UTF_8));
                postSnack(getString(R.string.export_success, assetCount, eventCount));
            } catch (Exception e) {
                Log.w(TAG, "export failed", e);
                postSnack(getString(R.string.export_failed,
                        e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
    }

    private void onImportPicked(@NonNull ActivityResult result) {
        Uri uri = (result.getData() != null) ? result.getData().getData() : null;
        if (uri == null) return;
        ServiceLocator sl = ServiceLocator.get(requireContext());
        ExecutorService bridge = sl.viewExecutor();
        bridge.execute(() -> {
            try {
                String json = readText(uri);
                // Hand off to the orchestrator: drives the same blocking overlay the
                // startup sync uses, runs the import, then rebuilds snapshots so the
                // chart reflects imported history immediately. The overlay disappearing
                // is the user-visible success signal.
                sl.startupSyncOrchestrator().runImport(json);
            } catch (Exception e) {
                Log.w(TAG, "import failed", e);
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                postSnack(getString(R.string.import_failed,
                        cause.getMessage() != null ? cause.getMessage() : cause.toString()));
            }
        });
    }

    private void writeBytes(@NonNull Uri uri, @NonNull byte[] bytes) throws Exception {
        try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("Could not open " + uri);
            out.write(bytes);
            out.flush();
        }
    }

    @NonNull
    private String readText(@NonNull Uri uri) throws Exception {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Could not open " + uri);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    private static int countMatches(@NonNull String haystack, @NonNull String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private void postSnack(@NonNull String text) {
        if (!isAdded() || binding == null) return;
        requireActivity().runOnUiThread(() ->
                Snackbar.make(binding.getRoot(), text, Snackbar.LENGTH_LONG).show());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
