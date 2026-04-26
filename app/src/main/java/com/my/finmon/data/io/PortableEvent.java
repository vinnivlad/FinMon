package com.my.finmon.data.io;

/**
 * One {@link com.my.finmon.data.entity.EventEntity} in serialized form.
 *
 * <p>Asset references are logical {@code (ticker, currency)} pairs rather than numeric
 * ids, so the JSON survives an id-changing wipe-and-restore.
 */
public final class PortableEvent {
    public String timestamp;
    public String type;
    public String assetTicker;
    public String assetCurrency;
    public String amount;
    public String price;
    public String incomeSourceAssetTicker;
    public String incomeSourceAssetCurrency;
}
