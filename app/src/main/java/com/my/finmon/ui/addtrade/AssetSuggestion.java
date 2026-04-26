package com.my.finmon.ui.addtrade;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.remote.nbu.NbuBondDto;
import com.my.finmon.data.remote.yahoo.YahooClient.SearchHit;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row in the trade-form's autocomplete dropdown. Wraps:
 * <ul>
 *   <li>{@link Source#LOCAL} — an asset already in the DB; currency + type known.</li>
 *   <li>{@link Source#REMOTE_STOCK} — a Yahoo search hit; currency resolved on pick.</li>
 *   <li>{@link Source#REMOTE_BOND} — an NBU bond; currency, face, yield, maturity all
 *       known from the NBU response (Y4).</li>
 * </ul>
 */
public final class AssetSuggestion {

    public enum Source { LOCAL, REMOTE_STOCK, REMOTE_BOND }

    @NonNull public final Source source;
    /** Local-asset id, or -1 for remote. */
    public final long localId;
    /** Domain ticker (e.g. {@code VOO}, or ISIN-derived label for bonds). */
    @NonNull public final String ticker;
    /** Yahoo symbol (e.g. {@code VOO}, {@code SXR8.DE}). Null for bonds. */
    @Nullable public final String remoteTicker;
    @Nullable public final String name;
    @Nullable public final String exchange;
    @NonNull public final AssetType type;
    /** Known up-front for LOCAL and REMOTE_BOND; null for REMOTE_STOCK (resolved on pick). */
    @Nullable public final Currency knownCurrency;

    // Bond-specific. Populated only for REMOTE_BOND (and LOCAL bonds whose data
    // is mirrored from a prior NBU pick — currently always null on local).
    @Nullable public final String isin;
    @Nullable public final BigDecimal bondFace;
    @Nullable public final BigDecimal bondYieldPct;
    @Nullable public final LocalDate bondMaturity;

    private AssetSuggestion(
            @NonNull Source source,
            long localId,
            @NonNull String ticker,
            @Nullable String remoteTicker,
            @Nullable String name,
            @Nullable String exchange,
            @NonNull AssetType type,
            @Nullable Currency knownCurrency,
            @Nullable String isin,
            @Nullable BigDecimal bondFace,
            @Nullable BigDecimal bondYieldPct,
            @Nullable LocalDate bondMaturity) {
        this.source = source;
        this.localId = localId;
        this.ticker = ticker;
        this.remoteTicker = remoteTicker;
        this.name = name;
        this.exchange = exchange;
        this.type = type;
        this.knownCurrency = knownCurrency;
        this.isin = isin;
        this.bondFace = bondFace;
        this.bondYieldPct = bondYieldPct;
        this.bondMaturity = bondMaturity;
    }

    @NonNull
    public static AssetSuggestion ofLocal(@NonNull AssetEntity a) {
        return new AssetSuggestion(
                Source.LOCAL, a.id, a.ticker, a.remoteTicker, a.name,
                /* exchange — not stored on local rows yet */ null,
                a.type, a.currency,
                a.isin, a.bondInitialPrice, a.bondYieldPct, a.bondMaturityDate);
    }

    @NonNull
    public static AssetSuggestion ofRemoteStock(@NonNull SearchHit h) {
        // Yahoo's symbol can carry an exchange suffix (e.g. SXR8.DE). Strip it for the
        // domain ticker so it matches the convention "ticker = clean symbol, remoteTicker
        // = exchange-suffixed". US tickers have no dot and pass through unchanged.
        int dot = h.symbol.indexOf('.');
        String cleanTicker = (dot >= 0) ? h.symbol.substring(0, dot) : h.symbol;
        return new AssetSuggestion(
                Source.REMOTE_STOCK, -1L, cleanTicker, h.symbol, h.name, h.exchange,
                AssetType.STOCK, null,
                null, null, null, null);
    }

    @NonNull
    public static AssetSuggestion ofRemoteBond(
            @NonNull String isin,
            @NonNull String name,
            @NonNull BigDecimal face,
            @NonNull BigDecimal yieldPct,
            @NonNull LocalDate maturity,
            @NonNull Currency currency) {
        return new AssetSuggestion(
                Source.REMOTE_BOND, -1L, isin, null, name, /* exchange */ "NBU",
                AssetType.BOND, currency,
                isin, face, yieldPct, maturity);
    }

    /**
     * Adapts an NBU DTO to a suggestion. Returns null for entries with missing
     * required fields, unparseable dates, or unsupported currencies — callers
     * skip those silently.
     */
    @Nullable
    public static AssetSuggestion ofNbuBond(@NonNull NbuBondDto b) {
        if (b.cpcode == null || b.nominal == null || b.auk_proc == null
                || b.pgs_date == null || b.val_code == null) return null;
        Currency ccy;
        try {
            ccy = Currency.valueOf(b.val_code);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        LocalDate maturity;
        try {
            maturity = LocalDate.parse(b.pgs_date);
        } catch (Exception ex) {
            return null;
        }
        String label = (b.cpdescr != null && !b.cpdescr.isEmpty()) ? b.cpdescr : b.cpcode;
        return ofRemoteBond(
                b.cpcode, label,
                BigDecimal.valueOf(b.nominal),
                BigDecimal.valueOf(b.auk_proc),
                maturity,
                ccy);
    }

    /** Single-line label shown in the dropdown. */
    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(ticker);
        if (exchange != null && !exchange.isEmpty()) {
            sb.append(" · ").append(exchange);
        } else if (knownCurrency != null) {
            sb.append(" · ").append(knownCurrency.name());
        }
        if (name != null && !name.isEmpty()) {
            sb.append(" · ").append(name);
        }
        return sb.toString();
    }
}
