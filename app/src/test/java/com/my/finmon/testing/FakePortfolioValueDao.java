package com.my.finmon.testing;

import androidx.lifecycle.LiveData;

import com.my.finmon.data.dao.PortfolioValueDao;
import com.my.finmon.data.entity.PortfolioValueSnapshotEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory fake of {@link PortfolioValueDao}. Keyed on date, upsert replaces.
 */
public final class FakePortfolioValueDao implements PortfolioValueDao {

    private final Map<LocalDate, PortfolioValueSnapshotEntity> rows = new LinkedHashMap<>();

    @Override
    public void upsert(PortfolioValueSnapshotEntity snapshot) {
        rows.put(snapshot.date, snapshot);
    }

    @Override
    public void upsertAll(List<PortfolioValueSnapshotEntity> snapshots) {
        for (PortfolioValueSnapshotEntity s : snapshots) upsert(s);
    }

    @Override
    public PortfolioValueSnapshotEntity find(LocalDate date) {
        return rows.get(date);
    }

    @Override
    public List<PortfolioValueSnapshotEntity> getRange(LocalDate from, LocalDate to) {
        List<PortfolioValueSnapshotEntity> out = new ArrayList<>();
        for (PortfolioValueSnapshotEntity s : rows.values()) {
            if (s.date.isBefore(from) || s.date.isAfter(to)) continue;
            out.add(s);
        }
        out.sort((a, b) -> a.date.compareTo(b.date));
        return out;
    }

    @Override
    public LiveData<List<PortfolioValueSnapshotEntity>> observeRange(LocalDate from, LocalDate to) {
        throw new UnsupportedOperationException("observeRange not supported in unit-test fakes");
    }

    @Override
    public LocalDate latestDate() {
        LocalDate max = null;
        for (LocalDate d : rows.keySet()) {
            if (max == null || d.isAfter(max)) max = d;
        }
        return max;
    }

    @Override
    public List<PortfolioValueSnapshotEntity> findGappyUpTo(LocalDate upTo) {
        List<PortfolioValueSnapshotEntity> out = new ArrayList<>();
        for (PortfolioValueSnapshotEntity s : rows.values()) {
            if (s.hasFxGaps && !s.date.isAfter(upTo)) out.add(s);
        }
        out.sort((a, b) -> a.date.compareTo(b.date));
        return out;
    }

    @Override
    public void deleteAll() {
        rows.clear();
    }
}
