package com.my.finmon.testing;

import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.entity.ExchangeRateEntity;
import com.my.finmon.data.model.Currency;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory fake of {@link ExchangeRateDao}. Keyed on (src, tgt, date), upsert replaces.
 */
public final class FakeExchangeRateDao implements ExchangeRateDao {

    private final Map<String, ExchangeRateEntity> rows = new LinkedHashMap<>();

    private static String key(Currency src, Currency tgt, LocalDate date) {
        return src.name() + "|" + tgt.name() + "|" + date;
    }

    @Override
    public void upsert(ExchangeRateEntity rate) {
        rows.put(key(rate.sourceCurrency, rate.targetCurrency, rate.date), rate);
    }

    @Override
    public void upsertAll(List<ExchangeRateEntity> rates) {
        for (ExchangeRateEntity r : rates) upsert(r);
    }

    @Override
    public ExchangeRateEntity find(Currency src, Currency tgt, LocalDate date) {
        return rows.get(key(src, tgt, date));
    }

    @Override
    public ExchangeRateEntity findMostRecent(Currency src, Currency tgt) {
        ExchangeRateEntity best = null;
        for (ExchangeRateEntity r : rows.values()) {
            if (r.sourceCurrency != src || r.targetCurrency != tgt) continue;
            if (best == null || r.date.isAfter(best.date)) best = r;
        }
        return best;
    }

    @Override
    public ExchangeRateEntity findOnOrBefore(Currency src, Currency tgt, LocalDate onOrBefore) {
        ExchangeRateEntity best = null;
        for (ExchangeRateEntity r : rows.values()) {
            if (r.sourceCurrency != src || r.targetCurrency != tgt) continue;
            if (r.date.isAfter(onOrBefore)) continue;
            if (best == null || r.date.isAfter(best.date)) best = r;
        }
        return best;
    }

    @Override
    public LocalDate latestDate(Currency src, Currency tgt) {
        ExchangeRateEntity r = findMostRecent(src, tgt);
        return r != null ? r.date : null;
    }

    @Override
    public List<ExchangeRateEntity> getRange(Currency src, Currency tgt, LocalDate from, LocalDate to) {
        List<ExchangeRateEntity> out = new ArrayList<>();
        for (ExchangeRateEntity r : rows.values()) {
            if (r.sourceCurrency != src || r.targetCurrency != tgt) continue;
            if (r.date.isBefore(from) || r.date.isAfter(to)) continue;
            out.add(r);
        }
        out.sort((a, b) -> a.date.compareTo(b.date));
        return out;
    }

    @Override
    public List<ExchangeRateEntity> getAll() {
        List<ExchangeRateEntity> out = new ArrayList<>(rows.values());
        out.sort((a, b) -> {
            int bySrc = a.sourceCurrency.compareTo(b.sourceCurrency);
            if (bySrc != 0) return bySrc;
            int byTgt = a.targetCurrency.compareTo(b.targetCurrency);
            return byTgt != 0 ? byTgt : a.date.compareTo(b.date);
        });
        return out;
    }

    @Override
    public void deleteAll() {
        rows.clear();
    }
}
