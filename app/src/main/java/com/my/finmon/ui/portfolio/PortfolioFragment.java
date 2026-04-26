package com.my.finmon.ui.portfolio;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.my.finmon.R;
import com.my.finmon.ServiceLocator;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.repository.ImportExportRepository;
import com.my.finmon.data.repository.ImportExportRepository.ImportResult;
import com.my.finmon.data.repository.PortfolioRepository.PortfolioTotals;
import com.my.finmon.databinding.FragmentPortfolioBinding;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class PortfolioFragment extends Fragment {

    private static final String TAG = "PortfolioFragment";

    private static final DecimalFormat MONEY = buildFormat("#,##0.00");
    private static final DecimalFormat SIGNED_MONEY = buildFormat("+#,##0.00;-#,##0.00");
    private static final DecimalFormat PCT = buildFormat("+0.0'%';-0.0'%'");
    private static final MathContext PCT_MC = new MathContext(4, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private FragmentPortfolioBinding binding;
    private PortfolioViewModel viewModel;
    private HoldingsAdapter adapter;

    /** SAF launchers — registered at construction time so they can be invoked from the FAB menu. */
    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::onExportPicked);
    private final ActivityResultLauncher<Intent> importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::onImportPicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPortfolioBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new HoldingsAdapter();
        binding.holdingsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.holdingsList.setAdapter(adapter);
        binding.holdingsList.addItemDecoration(
                new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        viewModel = new ViewModelProvider(
                this,
                PortfolioViewModel.factory(requireContext())
        ).get(PortfolioViewModel.class);

        viewModel.holdings().observe(getViewLifecycleOwner(), list -> {
            adapter.submitList(list);
            boolean empty = (list == null || list.isEmpty());
            binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        });

        viewModel.totals().observe(getViewLifecycleOwner(), this::bindTotals);

        binding.totalsCard.setOnClickListener(v ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_portfolio_to_breakdown));

        binding.fab.setOnClickListener(this::showFabMenu);
    }

    private void bindTotals(@Nullable PortfolioTotals t) {
        if (t == null) return;

        binding.totalAmount.setText(MONEY.format(t.valueInBase) + " " + t.baseCurrency.name());

        // Ribbon of the same total in other display currencies.
        StringBuilder others = new StringBuilder();
        for (Map.Entry<Currency, BigDecimal> e : t.valueByDisplayCurrency.entrySet()) {
            if (e.getKey() == t.baseCurrency) continue;
            if (others.length() > 0) others.append(" · ");
            others.append(MONEY.format(e.getValue())).append(' ').append(e.getKey().name());
        }
        if (others.length() > 0) {
            binding.totalDisplayEquivalents.setText("≈ " + others);
            binding.totalDisplayEquivalents.setVisibility(View.VISIBLE);
        } else {
            binding.totalDisplayEquivalents.setVisibility(View.GONE);
        }

        binding.totalInvested.setText(getString(
                R.string.totals_invested_label,
                MONEY.format(t.investedInBase) + " " + t.baseCurrency.name()));

        if (t.investedInBase.signum() != 0) {
            BigDecimal pct = t.pnlInBase.divide(t.investedInBase.abs(), PCT_MC).multiply(HUNDRED);
            binding.totalPnl.setText(
                    SIGNED_MONEY.format(t.pnlInBase) + " " + t.baseCurrency.name()
                            + " (" + PCT.format(pct) + ")");
        } else {
            binding.totalPnl.setText(SIGNED_MONEY.format(t.pnlInBase) + " " + t.baseCurrency.name());
        }
        int color = t.pnlInBase.signum() > 0
                ? R.color.pnl_positive
                : (t.pnlInBase.signum() < 0 ? R.color.pnl_negative : R.color.pnl_neutral);
        binding.totalPnl.setTextColor(ContextCompat.getColor(requireContext(), color));

        binding.fxGapHint.setVisibility(t.hasFxGaps ? View.VISIBLE : View.GONE);
    }

    private void showFabMenu(@NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.portfolio_fab_menu);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_add_asset) {
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_portfolio_to_addAsset);
                return true;
            }
            if (id == R.id.menu_record_trade) {
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_portfolio_to_addTrade);
                return true;
            }
            if (id == R.id.menu_export_data) {
                launchExport();
                return true;
            }
            if (id == R.id.menu_import_data) {
                launchImport();
                return true;
            }
            return false;
        });
        menu.show();
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
                // Accept JSON variants users may save under (some editors use text/plain).
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
        ImportExportRepository repo = sl.importExportRepository();
        ExecutorService bridge = sl.viewExecutor();
        bridge.execute(() -> {
            try {
                String json = readText(uri);
                ImportResult r = repo.importFromJson(json).get();
                postSnack(getString(R.string.import_success,
                        r.assetsImported, r.eventsImported, r.eventsEnriched));
                requireActivity().runOnUiThread(() -> {
                    if (viewModel != null) viewModel.refresh();
                });
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

    /** Quick-and-dirty hit count for snackbar feedback. Not parsing JSON twice for speed. */
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
    public void onResume() {
        super.onResume();
        // Repository isn't reactive — manual refresh covers the "came back from add-trade" case.
        if (viewModel != null) viewModel.refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private static DecimalFormat buildFormat(@NonNull String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat f = new DecimalFormat(pattern, sym);
        f.setParseBigDecimal(true);
        return f;
    }
}
