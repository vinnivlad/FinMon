package com.my.finmon.data.repository;

import androidx.annotation.NonNull;

import com.my.finmon.data.model.AssetType;

import java.math.BigDecimal;

/**
 * Resolves the default tax rate (as a percent: 15 means 15%) for a given asset type.
 * Per-asset overrides live on {@code AssetEntity.taxRatePct} — when null, the repo
 * falls back to this default.
 *
 * <p>Backed by {@code UserPreferences} in production, by {@link #ZERO} in tests where
 * the default-rate plumbing is irrelevant.
 */
public interface TaxRates {

    /** Default percent for {@code type}, or 0 if untaxed. Never null. */
    @NonNull
    BigDecimal defaultRate(@NonNull AssetType type);

    /** No-tax fallback for tests and any code path that doesn't care about taxes. */
    TaxRates ZERO = type -> BigDecimal.ZERO;
}
