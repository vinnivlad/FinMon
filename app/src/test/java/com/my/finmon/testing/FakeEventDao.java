package com.my.finmon.testing;

import androidx.lifecycle.LiveData;

import com.my.finmon.data.dao.EventDao;
import com.my.finmon.data.entity.EventEntity;
import com.my.finmon.data.model.EventType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory fake of {@link EventDao}. Assigns auto-incrementing ids on insert. Honors
 * the ordering contract on read paths (timestamp ASC, id ASC). The {@code @Transaction}
 * default method {@code insertTradePair} is inherited unchanged — it just calls
 * {@code insertAll}, which we implement here.
 */
public final class FakeEventDao extends EventDao {

    private final List<EventEntity> rows = new ArrayList<>();
    private long nextId = 1;

    @Override
    public long insert(EventEntity event) {
        event.id = nextId++;
        rows.add(event);
        return event.id;
    }

    @Override
    public List<Long> insertAll(List<EventEntity> events) {
        List<Long> ids = new ArrayList<>(events.size());
        for (EventEntity e : events) ids.add(insert(e));
        return ids;
    }

    @Override
    public List<EventEntity> getByAssetChronological(long assetId) {
        List<EventEntity> out = new ArrayList<>();
        for (EventEntity e : rows) if (e.assetId == assetId) out.add(e);
        out.sort(Comparator.<EventEntity, LocalDateTime>comparing(e -> e.timestamp)
                .thenComparingLong(e -> e.id));
        return out;
    }

    @Override
    public List<EventEntity> getByAssetAsOf(long assetId, LocalDateTime upTo) {
        List<EventEntity> out = new ArrayList<>();
        for (EventEntity e : rows) {
            if (e.assetId == assetId && !e.timestamp.isAfter(upTo)) out.add(e);
        }
        out.sort(Comparator.<EventEntity, LocalDateTime>comparing(e -> e.timestamp)
                .thenComparingLong(e -> e.id));
        return out;
    }

    @Override
    public LiveData<List<EventEntity>> observeRecent(int limit) {
        throw new UnsupportedOperationException("observeRecent not supported in unit-test fakes");
    }

    @Override
    public LocalDateTime earliestTimestamp() {
        LocalDateTime min = null;
        for (EventEntity e : rows) {
            if (min == null || e.timestamp.isBefore(min)) min = e.timestamp;
        }
        return min;
    }

    @Override
    public int countForAsset(long assetId) {
        int n = 0;
        for (EventEntity e : rows) if (e.assetId == assetId) n++;
        return n;
    }

    @Override
    public List<EventEntity> getIncomeFromAssetAsOf(long sourceAssetId, LocalDateTime upTo) {
        List<EventEntity> out = new ArrayList<>();
        for (EventEntity e : rows) {
            if (e.type != EventType.DIVIDEND) continue;
            if (e.incomeSourceAssetId == null || e.incomeSourceAssetId != sourceAssetId) continue;
            if (e.timestamp.isAfter(upTo)) continue;
            out.add(e);
        }
        out.sort(Comparator.<EventEntity, LocalDateTime>comparing(e -> e.timestamp)
                .thenComparingLong(e -> e.id));
        return out;
    }

    @Override
    public EventEntity findDividendOnDate(
            long sourceAssetId, LocalDateTime startOfDay, LocalDateTime endOfDayExclusive) {
        for (EventEntity e : rows) {
            if (e.type != EventType.DIVIDEND) continue;
            if (e.incomeSourceAssetId == null || e.incomeSourceAssetId != sourceAssetId) continue;
            if (e.timestamp.isBefore(startOfDay) || !e.timestamp.isBefore(endOfDayExclusive)) continue;
            return e;
        }
        return null;
    }

    @Override
    public EventEntity findSplitOnDate(
            long assetId, LocalDateTime startOfDay, LocalDateTime endOfDayExclusive) {
        for (EventEntity e : rows) {
            if (e.type != EventType.SPLIT) continue;
            if (e.assetId != assetId) continue;
            if (e.timestamp.isBefore(startOfDay) || !e.timestamp.isBefore(endOfDayExclusive)) continue;
            return e;
        }
        return null;
    }

    @Override
    public EventEntity findMaturityForAsset(long bondAssetId) {
        for (EventEntity e : rows) {
            if (e.type != EventType.MATURITY) continue;
            if (e.incomeSourceAssetId == null || e.incomeSourceAssetId != bondAssetId) continue;
            return e;
        }
        return null;
    }

    @Override
    public LocalDateTime findLatestDividendTimestamp(long sourceAssetId) {
        LocalDateTime max = null;
        for (EventEntity e : rows) {
            if (e.type != EventType.DIVIDEND) continue;
            if (e.incomeSourceAssetId == null || e.incomeSourceAssetId != sourceAssetId) continue;
            if (max == null || e.timestamp.isAfter(max)) max = e.timestamp;
        }
        return max;
    }

    @Override
    public List<Long> findMaturedBondIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (EventEntity e : rows) {
            if (e.type == EventType.MATURITY && e.incomeSourceAssetId != null) {
                ids.add(e.incomeSourceAssetId);
            }
        }
        return new ArrayList<>(ids);
    }

    @Override
    public int countNonCashEventsAt(LocalDateTime ts) {
        // Asset-type lookup needs the AssetDao; tests pass a separate FakeAssetDao through
        // PortfolioRepository, so this fake can't peek. Returning the count of non-CASH
        // events here would require coupling — instead, we encode "this event sits on an
        // asset whose type != CASH" by inspecting the EventEntity directly. That's fine
        // for the math tests because trade legs are always paired (asset event + cash
        // event); the asset-side row at the same timestamp is the signal.
        //
        // Behavior: count events at this exact timestamp that are NOT cash flows. We
        // approximate "not on a cash asset" by "type IN (IN, OUT) AND incomeSourceAssetId
        // is null AND assetId not registered as a cash asset". Tests register cash assets
        // via TestFixture which adds them to cashAssetIds.
        int n = 0;
        for (EventEntity e : rows) {
            if (!e.timestamp.equals(ts)) continue;
            if (cashAssetIds.contains(e.assetId)) continue;
            n++;
        }
        return n;
    }

    @Override
    public List<EventEntity> getAllChronological() {
        List<EventEntity> out = new ArrayList<>(rows);
        out.sort(Comparator.<EventEntity, LocalDateTime>comparing(e -> e.timestamp)
                .thenComparingLong(e -> e.id));
        return out;
    }

    @Override
    public List<EventEntity> getAllReverseChronological() {
        List<EventEntity> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        return reversed;
    }

    @Override
    public void deleteAll() {
        rows.clear();
        nextId = 1;
    }

    /**
     * Asset ids the fixture has registered as CASH piles. Drives
     * {@link #countNonCashEventsAt} since the fake DAO can't reach
     * {@link com.my.finmon.data.dao.AssetDao} on its own.
     */
    private final Set<Long> cashAssetIds = new LinkedHashSet<>();

    void registerCashAssetId(long id) {
        cashAssetIds.add(id);
    }
}
