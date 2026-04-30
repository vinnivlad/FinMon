package com.my.finmon;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.my.finmon.databinding.ActivityMainBinding;
import com.my.finmon.sync.StartupSyncOrchestrator;
import com.my.finmon.sync.StartupSyncOrchestrator.Stage;
import com.my.finmon.sync.StartupSyncOrchestrator.Status;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private StartupSyncOrchestrator orchestrator;
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        navController = navHost.getNavController();

        AppBarConfiguration appBarConfig = new AppBarConfiguration.Builder(
                R.id.portfolioFragment,
                R.id.chartFragment,
                R.id.marketFragment,
                R.id.settingsFragment
        ).build();

        BottomNavigationView bottomNav = binding.bottomNav;
        // setupWithNavController also installs a destination-changed listener that keeps
        // the selected tab in sync with the current destination — we keep that and only
        // override the tap handler below so the multi-back-stack save/restore is bypassed.
        NavigationUI.setupWithNavController(bottomNav, navController);

        // Default behavior (Navigation 2.4+): each tab keeps its own back stack and
        // restores the deepest destination on re-selection. The user wants tab taps to
        // always land on the tab's root, so we navigate with popUpTo=startDestination
        // and disable saveState/restoreState.
        bottomNav.setOnItemSelectedListener(item -> {
            int destId = item.getItemId();
            int startDest = navController.getGraph().getStartDestinationId();
            NavOptions options = new NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setRestoreState(false)
                    .setPopUpTo(startDest, /* inclusive */ false, /* saveState */ false)
                    .build();
            try {
                navController.navigate(destId, null, options);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        });

        orchestrator = ServiceLocator.get(this).startupSyncOrchestrator();
        orchestrator.status().observe(this, this::renderStartupStatus);

        binding.startupRetry.setOnClickListener(v -> orchestrator.retry());
        binding.startupContinue.setOnClickListener(v -> orchestrator.dismissAfterFailure());
    }

    private void renderStartupStatus(Status s) {
        if (s == null) return;

        if (s.stage == Stage.DONE) {
            binding.startupOverlay.setVisibility(View.GONE);
            binding.bottomNav.setVisibility(View.VISIBLE);
            // After a successful import, jump to Portfolio so the user sees the freshly
            // restored data instead of the Settings screen they triggered Import from.
            // consumeImportJustFinished() is one-shot so rotation doesn't re-navigate.
            if (orchestrator.consumeImportJustFinished()) {
                NavOptions options = new NavOptions.Builder()
                        .setLaunchSingleTop(true)
                        .setPopUpTo(navController.getGraph().getStartDestinationId(),
                                /* inclusive */ false, /* saveState */ false)
                        .build();
                try {
                    navController.navigate(R.id.portfolioFragment, null, options);
                } catch (IllegalArgumentException ignored) {
                    // Already on Portfolio, or graph not ready — safe to drop.
                }
            }
            return;
        }

        // Hide the bottom nav explicitly: BottomNavigationView's default elevation lets
        // it bleed through the overlay's z-order, so the overlay alone isn't enough.
        binding.startupOverlay.setVisibility(View.VISIBLE);
        binding.bottomNav.setVisibility(View.GONE);

        boolean failed = (s.stage == Stage.FAILED);
        binding.startupProgress.setVisibility(failed ? View.GONE : View.VISIBLE);
        binding.startupErrorTitle.setVisibility(failed ? View.VISIBLE : View.GONE);
        binding.startupErrorMessage.setVisibility(failed ? View.VISIBLE : View.GONE);
        binding.startupRetry.setVisibility(failed ? View.VISIBLE : View.GONE);
        binding.startupContinue.setVisibility(failed ? View.VISIBLE : View.GONE);

        if (failed) {
            binding.startupStatus.setVisibility(View.GONE);
            String msg;
            if (StartupSyncOrchestrator.ERROR_NO_INTERNET.equals(s.errorMessage)) {
                msg = getString(R.string.startup_failed_no_internet);
            } else if (s.failedStage != null) {
                // Stage-tagged structured failure: prefix with the stage name so the user
                // immediately knows which step broke (Bond coupons, Stock prices, etc.).
                msg = getString(R.string.startup_failed_at_stage,
                        stageDisplayName(s.failedStage),
                        s.errorMessage != null ? s.errorMessage : "");
            } else {
                msg = getString(R.string.startup_failed_message,
                        s.errorMessage != null ? s.errorMessage : "");
            }
            binding.startupErrorMessage.setText(msg);
            return;
        }

        binding.startupStatus.setVisibility(View.VISIBLE);
        binding.startupStatus.setText(stageLabel(s));
    }

    private String stageDisplayName(@NonNull Stage stage) {
        switch (stage) {
            case IMPORTING:    return getString(R.string.stage_name_importing);
            case STOCK_PRICES: return getString(R.string.stage_name_stock_prices);
            case FX:           return getString(R.string.stage_name_fx);
            case BOND_COUPONS: return getString(R.string.stage_name_bond_coupons);
            case SNAPSHOTS:    return getString(R.string.stage_name_snapshots);
            default:           return getString(R.string.stage_name_other);
        }
    }

    private String stageLabel(Status s) {
        switch (s.stage) {
            case IDLE:
                return getString(R.string.startup_initializing);
            case STARTING:
                return getString(R.string.startup_starting);
            case IMPORTING:
                return getString(R.string.startup_stage_importing);
            case STOCK_PRICES:
                if (s.totalItems > 0) {
                    return getString(R.string.startup_stage_stock_prices,
                            s.label, s.currentItem, s.totalItems);
                }
                return getString(R.string.startup_stage_stock_prices_indeterminate);
            case FX:
                return getString(R.string.startup_stage_fx);
            case BOND_COUPONS:
                if (s.totalItems > 0) {
                    return getString(R.string.startup_stage_bond_coupons,
                            s.label, s.currentItem, s.totalItems);
                }
                return getString(R.string.startup_stage_bond_coupons_indeterminate);
            case SNAPSHOTS:
                if (s.totalItems > 0) {
                    return getString(R.string.startup_stage_snapshots,
                            s.label, s.currentItem, s.totalItems);
                }
                return getString(R.string.startup_stage_snapshots_indeterminate);
            default:
                return "";
        }
    }
}
