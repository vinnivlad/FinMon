package com.my.finmon.testing;

import androidx.lifecycle.LiveData;

import com.my.finmon.data.dao.AssetDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory fake of {@link AssetDao} for unit tests. Assigns auto-incrementing ids on
 * insert. Methods that the production math doesn't reach throw — so tests that
 * accidentally exercise unmodeled paths fail loud instead of silently passing.
 */
public final class FakeAssetDao implements AssetDao {

    private final Map<Long, AssetEntity> byId = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public long insert(AssetEntity asset) {
        asset.id = nextId++;
        byId.put(asset.id, asset);
        return asset.id;
    }

    @Override
    public long insertIfAbsent(AssetEntity asset) {
        AssetEntity existing = findByTickerAndCurrency(asset.ticker, asset.currency);
        if (existing != null) return existing.id;
        return insert(asset);
    }

    @Override
    public void update(AssetEntity asset) {
        if (!byId.containsKey(asset.id)) {
            throw new IllegalStateException("update of unknown asset " + asset.id);
        }
        byId.put(asset.id, asset);
    }

    @Override
    public AssetEntity findById(long id) {
        return byId.get(id);
    }

    @Override
    public AssetEntity findByTickerAndCurrency(String ticker, Currency currency) {
        for (AssetEntity a : byId.values()) {
            if (a.ticker.equals(ticker) && a.currency == currency) return a;
        }
        return null;
    }

    @Override
    public List<AssetEntity> findByType(AssetType type) {
        List<AssetEntity> out = new ArrayList<>();
        for (AssetEntity a : byId.values()) {
            if (a.type == type) out.add(a);
        }
        out.sort(Comparator.comparing(a -> a.ticker));
        return out;
    }

    @Override
    public List<AssetEntity> getAll() {
        List<AssetEntity> out = new ArrayList<>(byId.values());
        out.sort(Comparator.<AssetEntity, String>comparing(a -> a.type.name())
                .thenComparing(a -> a.ticker));
        return out;
    }

    @Override
    public LiveData<List<AssetEntity>> observeAll() {
        throw new UnsupportedOperationException("observeAll not supported in unit-test fakes");
    }

    @Override
    public void deleteAll() {
        byId.clear();
        nextId = 1;
    }
}
