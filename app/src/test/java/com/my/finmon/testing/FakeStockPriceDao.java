package com.my.finmon.testing;

import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.StockPriceEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory fake of {@link StockPriceDao}. Keyed on (ticker, date), upsert replaces.
 */
public final class FakeStockPriceDao implements StockPriceDao {

    private final Map<String, StockPriceEntity> rows = new LinkedHashMap<>();

    private static String key(String ticker, LocalDate date) {
        return ticker + "|" + date;
    }

    @Override
    public void upsert(StockPriceEntity price) {
        rows.put(key(price.ticker, price.date), price);
    }

    @Override
    public void upsertAll(List<StockPriceEntity> prices) {
        for (StockPriceEntity p : prices) upsert(p);
    }

    @Override
    public StockPriceEntity find(String ticker, LocalDate date) {
        return rows.get(key(ticker, date));
    }

    @Override
    public StockPriceEntity findMostRecent(String ticker) {
        StockPriceEntity best = null;
        for (StockPriceEntity p : rows.values()) {
            if (!p.ticker.equals(ticker)) continue;
            if (best == null || p.date.isAfter(best.date)) best = p;
        }
        return best;
    }

    @Override
    public StockPriceEntity findOnOrBefore(String ticker, LocalDate onOrBefore) {
        StockPriceEntity best = null;
        for (StockPriceEntity p : rows.values()) {
            if (!p.ticker.equals(ticker)) continue;
            if (p.date.isAfter(onOrBefore)) continue;
            if (best == null || p.date.isAfter(best.date)) best = p;
        }
        return best;
    }

    @Override
    public LocalDate latestDate(String ticker) {
        StockPriceEntity p = findMostRecent(ticker);
        return p != null ? p.date : null;
    }

    @Override
    public List<StockPriceEntity> getRange(String ticker, LocalDate from, LocalDate to) {
        List<StockPriceEntity> out = new ArrayList<>();
        for (StockPriceEntity p : rows.values()) {
            if (!p.ticker.equals(ticker)) continue;
            if (p.date.isBefore(from) || p.date.isAfter(to)) continue;
            out.add(p);
        }
        out.sort((a, b) -> a.date.compareTo(b.date));
        return out;
    }

    @Override
    public void deleteAll() {
        rows.clear();
    }
}
