package com.my.finmon.data.io;

/**
 * One {@link com.my.finmon.data.entity.AssetEntity} in serialized form.
 *
 * <p>Numeric ids are deliberately omitted — they're auto-generated and would clash
 * with cash-pile ids on import. Events reference assets by the {@code (ticker, currency)}
 * unique-index pair instead.
 *
 * <p>Enums (currency, type) and BigDecimal / LocalDate fields are serialized as strings
 * to keep the JSON readable and avoid Moshi adapter setup. Numeric strings preserve full
 * BigDecimal precision.
 */
public final class PortableAsset {
    public String ticker;
    public String currency;
    public String type;
    public String remoteTicker;
    public String name;
    public String isin;
    public String bondMaturityDate;
    public String bondInitialPrice;
    public String bondYieldPct;
}
