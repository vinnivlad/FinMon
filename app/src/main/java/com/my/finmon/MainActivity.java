package com.my.finmon;

import android.animation.ObjectAnimator;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.my.finmon.databinding.ActivityMainBinding;
import com.my.finmon.security.AppLockState;
import com.my.finmon.sync.StartupSyncOrchestrator;
import com.my.finmon.sync.StartupSyncOrchestrator.RefreshOutcome;
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
    /** How long the check / cross sits in place of the refresh icon after a manual run. */
    private static final long REFRESH_BADGE_MS = 1600L;

    /** Lazily built on the first background sync; reused for every spin afterwards. */
    @androidx.annotation.Nullable private ObjectAnimator refreshSpin;
    private final Runnable clearRefreshBadge =
            () -> setRefreshIcon(R.drawable.ic_refresh, R.color.fm_ink_soft);

    /**
     * Foreground auto-refresh. While the app is resumed the orchestrator is nudged on this
     * cadence so the portfolio keeps re-marking against live prices without the user having
     * to leave and come back. The orchestrator applies its own staleness gate, so the tick
     * on resume is free when a sync already ran recently.
     */
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefreshTick = new Runnable() {
        @Override
        public void run() {
            if (orchestrator != null) orchestrator.refreshQuietly();
            refreshHandler.postDelayed(
                    this, StartupSyncOrchestrator.QUIET_REFRESH_INTERVAL_MS);
        }
    };

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

        // Settings is reachable from the masthead gear, always visible across
        // destinations. Skip when we're already on Settings — without an explicit
        // guard plus singleTop, tapping the gear from Settings stacks duplicate
        // SettingsFragment instances on the back stack.
        binding.masthead.mastheadSettingsButton.setOnClickListener(v -> {
            NavDestination current = navController.getCurrentDestination();
            if (current != null && current.getId() == R.id.settingsFragment) return;
            NavOptions opts = new NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .build();
            try {
                navController.navigate(R.id.settingsFragment, null, opts);
            } catch (IllegalArgumentException ignored) {
                // Graph not ready yet — drop silently.
            }
        });

        // Manual refresh, next to the gear. Same reasoning as the gear for the placement:
        // the masthead is the only chrome present on every destination, so market data can
        // be pulled from wherever the user happens to be standing.
        binding.masthead.mastheadRefreshButton.setOnClickListener(v -> orchestrator.refreshNow());
        orchestrator.backgroundSyncActive().observe(this, this::renderRefreshSpin);

        binding.masthead.mastheadDate.setText(formatTodayKicker());

        navController.addOnDestinationChangedListener(this::onDestinationChanged);

        binding.lockUnlockButton.setOnClickListener(v -> promptUnlock());
    }

    /**
     * "Friday · 2 May 2026" — locale-aware day-of-week + month name. Set once at
     * activity creation; the value only changes if the user keeps the app open across
     * midnight, which we accept as a non-issue.
     */
    private String formatTodayKicker() {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                .ofPattern("EEEE · d MMMM y", java.util.Locale.getDefault());
        return today.format(fmt);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // First foregrounding (cold start) AND every return from background land
        // here. If the process isn't unlocked, gate everything behind biometric/
        // credential prompt so a stolen-but-unlocked device can't peek at data.
        // AppLockState lives at the process level — config-change activity
        // recreations (theme switch, rotation, locale change) preserve the flag,
        // only real backgrounding clears it.
        //
        // The overlay defaults to visible in XML for cold-start safety; if we're
        // already unlocked (i.e. this is a config-change recreation after the user
        // already authenticated this session), explicitly hide it.
        if (AppLockState.isUnlocked()) {
            hideLockOverlay();
        } else if (!promptInFlight) {
            showLockOverlay();
            promptUnlock();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // After import/trade the held-currency set may have changed — re-derive so the
        // chip row stays in sync with reality.
        if (filterVm != null) filterVm.refreshAvailableCurrencies();

        // Restart the refresh cadence from now. The immediate first tick is what covers the
        // "app was warm in the background overnight" case that the cold-start sync misses.
        refreshHandler.removeCallbacks(autoRefreshTick);
        refreshHandler.post(autoRefreshTick);
    }

    @Override
    protected void onPause() {
        // Nothing to refresh for while backgrounded; PortfolioSyncWorker owns that window.
        refreshHandler.removeCallbacks(autoRefreshTick);
        super.onPause();
    }

    /**
     * Spins the masthead refresh icon while a background sync is in flight, then holds a
     * result badge for a moment if the run was one the user asked for.
     *
     * <p>The badge exists because the spin alone isn't legible: a sync over a warm
     * connection can finish inside a single frame, and LiveData coalesces a
     * {@code postValue(true)} immediately followed by {@code postValue(false)} into just the
     * last one — so on a fast refresh the icon may never visibly turn at all. The badge
     * doesn't depend on the timing, which is what makes it the actual signal.
     *
     * <p>The button stays enabled throughout — the orchestrator drops a tap that lands on
     * an in-flight run, and a spinning icon already says "working", so greying it out would
     * only add a dead-looking control.
     */
    private void renderRefreshSpin(Boolean active) {
        boolean spinning = Boolean.TRUE.equals(active);
        if (spinning) {
            // A new run supersedes any badge still on screen.
            refreshHandler.removeCallbacks(clearRefreshBadge);
            setRefreshIcon(R.drawable.ic_refresh, R.color.fm_ink_soft);
            if (refreshSpin == null) {
                refreshSpin = ObjectAnimator.ofFloat(
                        binding.masthead.mastheadRefreshButton, View.ROTATION, 0f, 360f);
                refreshSpin.setDuration(900L);
                refreshSpin.setRepeatCount(ObjectAnimator.INFINITE);
                refreshSpin.setInterpolator(new LinearInterpolator());
            }
            if (!refreshSpin.isStarted()) refreshSpin.start();
            return;
        }

        if (refreshSpin != null) {
            refreshSpin.cancel();
            binding.masthead.mastheadRefreshButton.setRotation(0f);
        }

        // Null outcome = a timed tick, which reports nothing. consume* is one-shot, so
        // rotation won't re-show a result the user has already seen.
        RefreshOutcome outcome = (orchestrator == null) ? null : orchestrator.consumeManualOutcome();
        if (outcome == null) return;

        boolean ok = (outcome == RefreshOutcome.SUCCESS);
        setRefreshIcon(
                ok ? R.drawable.ic_check : R.drawable.ic_close,
                ok ? R.color.pnl_positive : R.color.pnl_negative);
        refreshHandler.postDelayed(clearRefreshBadge, REFRESH_BADGE_MS);

        // The badge says something went wrong; the Snackbar is what says why.
        if (!ok) {
            Snackbar.make(
                            binding.getRoot(),
                            R.string.masthead_refresh_failed,
                            Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.bottomNav)
                    .show();
        }
    }

    private void setRefreshIcon(@DrawableRes int icon, @ColorRes int tint) {
        ImageButton button = binding.masthead.mastheadRefreshButton;
        button.setImageResource(icon);
        ImageViewCompat.setImageTintList(
                button, ColorStateList.valueOf(ContextCompat.getColor(this, tint)));
    }

    private void showLockOverlay() {
        binding.lockOverlay.setVisibility(View.VISIBLE);
        // Bottom nav / masthead / filter have default Material elevation that bleeds
        // through the overlay z-order — hide them explicitly while locked, mirroring
        // the startupOverlay pattern in renderStartupStatus().
        binding.bottomNav.setVisibility(View.GONE);
        binding.masthead.getRoot().setVisibility(View.GONE);
        binding.globalFilter.getRoot().setVisibility(View.GONE);
    }

    private void hideLockOverlay() {
        binding.lockOverlay.setVisibility(View.GONE);
        binding.bottomNav.setVisibility(View.VISIBLE);
        binding.masthead.getRoot().setVisibility(View.VISIBLE);
        // Filter visibility follows FILTER_DESTINATIONS — masthead is always on.
        NavDestination dest = navController != null ? navController.getCurrentDestination() : null;
        boolean filterShouldShow = dest != null && FILTER_DESTINATIONS.contains(dest.getId());
        binding.globalFilter.getRoot().setVisibility(filterShouldShow ? View.VISIBLE : View.GONE);
    }

    private void promptUnlock() {
        BiometricManager bm = BiometricManager.from(this);
        if (bm.canAuthenticate(LOCK_AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No usable credentials on the device — biometrics not enrolled and no
            // PIN/pattern set. We can't enforce the lock without locking the user
            // out entirely, so let them in. The overlay still hides cleanly.
            AppLockState.markUnlocked();
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
                        AppLockState.markUnlocked();
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
        // Masthead is always visible; only its title changes per destination.
        binding.masthead.mastheadTitle.setText(titleForDestination(destination.getId()));

        // Filter chip rows hide on screens that don't react to the global filter.
        boolean showFilter = FILTER_DESTINATIONS.contains(destination.getId());
        binding.globalFilter.getRoot().setVisibility(showFilter ? View.VISIBLE : View.GONE);
    }

    private int titleForDestination(int destinationId) {
        if (destinationId == R.id.portfolioFragment)         return R.string.portfolio_title;
        if (destinationId == R.id.bondsFragment)             return R.string.bonds_title;
        if (destinationId == R.id.currencyBreakdownFragment) return R.string.breakdown_title;
        if (destinationId == R.id.chartsFragment)            return R.string.charts_title;
        if (destinationId == R.id.marketFragment)            return R.string.market_title;
        if (destinationId == R.id.settingsFragment)          return R.string.settings_title;
        if (destinationId == R.id.addAssetFragment)          return R.string.add_asset_title;
        if (destinationId == R.id.addTradeFragment)          return R.string.add_trade_title;
        if (destinationId == R.id.cashFragment)              return R.string.cash_title;
        if (destinationId == R.id.manualEventFragment)       return R.string.manual_event_title;
        if (destinationId == R.id.assetTaxOverridesFragment) return R.string.tax_overrides_title;
        if (destinationId == R.id.eventLogFragment)          return R.string.event_log_title;
        return R.string.app_name;
    }

    private void renderStartupStatus(Status s) {
        if (s == null) return;

        if (s.stage == Stage.DONE) {
            binding.startupOverlay.setVisibility(View.GONE);
            binding.bottomNav.setVisibility(View.VISIBLE);
            binding.masthead.getRoot().setVisibility(View.VISIBLE);
            // Filter visibility follows FILTER_DESTINATIONS — masthead is always on.
            NavDestination dest = navController.getCurrentDestination();
            boolean filterShouldShow = dest != null
                    && FILTER_DESTINATIONS.contains(dest.getId());
            binding.globalFilter.getRoot().setVisibility(
                    filterShouldShow ? View.VISIBLE : View.GONE);
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

        // Hide the bottom nav, masthead, and filter explicitly: their default elevation
        // lets them bleed through the overlay's z-order, so the overlay alone isn't enough.
        binding.startupOverlay.setVisibility(View.VISIBLE);
        binding.bottomNav.setVisibility(View.GONE);
        binding.masthead.getRoot().setVisibility(View.GONE);
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
