package com.my.finmon;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.my.finmon.databinding.ActivityMainBinding;
import com.my.finmon.sync.StartupSyncOrchestrator;
import com.my.finmon.sync.StartupSyncOrchestrator.Stage;
import com.my.finmon.sync.StartupSyncOrchestrator.Status;
import com.my.finmon.ui.filter.GlobalFilterBinder;
import com.my.finmon.ui.filter.GlobalFilterViewModel;

import java.util.Set;

public class MainActivity extends AppCompatActivity {

    /**
     * BIOMETRIC_WEAK (face/iris/Class 2) + DEVICE_CREDENTIAL (PIN/pattern/password).
     * STRONG would also be accepted since fingerprint sensors satisfy WEAK; using
     * STRONG explicitly would lock out face-unlock-only devices, which is needlessly
     * strict for "is the device owner here?" — we don't crypto-bind anything.
     */
    private static final int LOCK_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private ActivityMainBinding binding;
    private StartupSyncOrchestrator orchestrator;
    private NavController navController;
    private GlobalFilterViewModel filterVm;

    /** True after the user authenticated; cleared on every {@link #onStop()} so the
     *  next foregrounding re-prompts. Banking-app behavior. */
    private boolean unlocked;
    /** Set while the system biometric/credential sheet is in front. Some devices
     *  put the calling activity through onStop while it's showing — without this
     *  flag we'd race to re-lock and re-prompt mid-prompt. */
    private boolean promptInFlight;

    /** Destinations where the global filter bar is visible. Anything else (Market,
     *  Settings, modal sub-screens like AddTrade) hides the bar. */
    private static final Set<Integer> FILTER_DESTINATIONS = Set.of(
            R.id.portfolioFragment,
            R.id.bondsFragment,
            R.id.currencyBreakdownFragment,
            R.id.chartsFragment
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // targetSdk 36 is edge-to-edge by default — the system bars draw transparent
        // over the activity, so without this padding the global filter chips would
        // sit under the status bar / camera cutout. Applying systemBars insets to the
        // root means whichever child sits topmost (filter bar, or nav host when the
        // filter is hidden on Market/Settings) gets the breathing room.
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        navController = navHost.getNavController();

        AppBarConfiguration appBarConfig = new AppBarConfiguration.Builder(
                R.id.portfolioFragment,
                R.id.bondsFragment,
                R.id.currencyBreakdownFragment,
                R.id.chartsFragment,
                R.id.marketFragment
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

        // Activity-scoped filter VM — Portfolio, Bonds, Breakdown, Chart all read it
        // via the same ViewModelProvider(activity) call.
        filterVm = new ViewModelProvider(this, GlobalFilterViewModel.factory(this))
                .get(GlobalFilterViewModel.class);
        new GlobalFilterBinder(binding.globalFilter, filterVm, this, this);

        // Settings was demoted from the bottom nav (5-item cap). The gear icon in
        // the filter bar's action row reaches it from any screen.
        binding.globalFilter.globalSettingsButton.setOnClickListener(v -> {
            try {
                navController.navigate(R.id.settingsFragment);
            } catch (IllegalArgumentException ignored) {
                // No-op: graph not ready or already on Settings.
            }
        });

        navController.addOnDestinationChangedListener(this::onDestinationChanged);

        binding.lockUnlockButton.setOnClickListener(v -> promptUnlock());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // First foregrounding (cold start) AND every return from background land
        // here. If we're not unlocked, gate everything behind biometric/credential
        // prompt so a stolen-but-unlocked device can't peek at portfolio data.
        if (!unlocked && !promptInFlight) {
            showLockOverlay();
            promptUnlock();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Re-lock on backgrounding so the next return foregrounds the prompt again.
        // promptInFlight guards against the system biometric sheet bouncing us
        // through onStop while we're mid-authentication.
        if (!promptInFlight) {
            unlocked = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // After import/trade the held-currency set may have changed — re-derive so the
        // chip row stays in sync with reality.
        if (filterVm != null) filterVm.refreshAvailableCurrencies();
    }

    private void showLockOverlay() {
        binding.lockOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLockOverlay() {
        binding.lockOverlay.setVisibility(View.GONE);
    }

    private void promptUnlock() {
        BiometricManager bm = BiometricManager.from(this);
        if (bm.canAuthenticate(LOCK_AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No usable credentials on the device — biometrics not enrolled and no
            // PIN/pattern set. We can't enforce the lock without locking the user
            // out entirely, so let them in. The overlay still hides cleanly.
            unlocked = true;
            hideLockOverlay();
            return;
        }

        promptInFlight = true;
        BiometricPrompt prompt = new BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        promptInFlight = false;
                        unlocked = true;
                        hideLockOverlay();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                       @NonNull CharSequence errString) {
                        promptInFlight = false;
                        // User canceled or hit back — drop them out of the app rather
                        // than leaving a stuck unlock screen with no obvious next step.
                        // The Retry button on the overlay covers the case where the
                        // system dismissed the prompt for transient reasons.
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED
                                || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || errorCode == BiometricPrompt.ERROR_CANCELED) {
                            finishAndRemoveTask();
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // Wrong fingerprint/face — system prompt stays open for retry.
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_lock_prompt_title))
                .setSubtitle(getString(R.string.app_lock_prompt_subtitle))
                .setAllowedAuthenticators(LOCK_AUTHENTICATORS)
                .build();
        prompt.authenticate(info);
    }

    private void onDestinationChanged(
            @NonNull NavController controller,
            @NonNull NavDestination destination,
            @androidx.annotation.Nullable Bundle args) {
        // The action row (Settings gear) stays visible everywhere; only the chip
        // rows hide on screens that don't react to the global filter.
        boolean showChips = FILTER_DESTINATIONS.contains(destination.getId());
        binding.globalFilter.globalFilterChipsContainer.setVisibility(
                showChips ? View.VISIBLE : View.GONE);
    }

    private void renderStartupStatus(Status s) {
        if (s == null) return;

        if (s.stage == Stage.DONE) {
            binding.startupOverlay.setVisibility(View.GONE);
            binding.bottomNav.setVisibility(View.VISIBLE);
            // Action row (gear) is always visible; chip container follows the same
            // FILTER_DESTINATIONS rule as onDestinationChanged.
            binding.globalFilter.getRoot().setVisibility(View.VISIBLE);
            NavDestination dest = navController.getCurrentDestination();
            boolean chipsShouldShow = dest != null
                    && FILTER_DESTINATIONS.contains(dest.getId());
            binding.globalFilter.globalFilterChipsContainer.setVisibility(
                    chipsShouldShow ? View.VISIBLE : View.GONE);
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

        // Hide the bottom nav and filter bar explicitly: their default elevation lets
        // them bleed through the overlay's z-order, so the overlay alone isn't enough.
        binding.startupOverlay.setVisibility(View.VISIBLE);
        binding.bottomNav.setVisibility(View.GONE);
        binding.globalFilter.getRoot().setVisibility(View.GONE);

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
