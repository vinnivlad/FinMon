package com.my.finmon.data.io;

import java.util.List;

/**
 * Top-level FinMon export shape. Round-trips through JSON via Moshi reflection.
 *
 * <p>{@code version} lets future schema changes skip incompatible files instead of
 * silently corrupting state.
 */
public final class PortableExport {
    public int version;
    public String exportedAt;
    public List<PortableAsset> assets;
    public List<PortableEvent> events;
}
