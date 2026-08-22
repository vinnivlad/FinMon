package com.my.finmon.sync;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide "market data changed" signal.
 *
 * <p>The repository layer isn't reactive, so screens refresh in {@code onResume} and when
 * the global filter moves. That covers navigation but not a sync that lands while the user
 * is sitting on a screen — which is exactly what the quiet 15-minute refresh does. ViewModels
 * observe {@link #revision()} the same way they observe the filter, and re-derive when it
 * ticks.
 *
 * <p>Static and process-scoped on purpose, mirroring {@code AppLockState}: every observer is
 * a ViewModel that removes itself in {@code onCleared}, and the signal has no per-Activity
 * meaning. The value itself is a meaningless monotonic counter — only the change matters.
 */
public final class MarketDataRefreshBus {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final MutableLiveData<Long> REVISION = new MutableLiveData<>(0L);

    private MarketDataRefreshBus() {}

    @NonNull
    public static LiveData<Long> revision() {
        return REVISION;
    }

    /** Called after a sync writes fresh prices / FX / events. Safe from any thread. */
    public static void bump() {
        REVISION.postValue(COUNTER.incrementAndGet());
    }
}
