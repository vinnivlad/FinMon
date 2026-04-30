package com.my.finmon.data.io;

/**
 * Serialized form of user-facing preferences. Round-trips alongside assets + events
 * in {@link PortableExport} so a fresh install can be restored from a single JSON.
 *
 * <p>All fields are nullable so older exports (pre-tax-settings) still parse — the
 * importer treats missing values as "keep the current setting" rather than overwrite.
 */
public final class PortableSettings {
    public String displayCurrency;
    public String defaultStockTaxPct;
    public String defaultBondTaxPct;
}
