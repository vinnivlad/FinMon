package com.my.finmon.data.repository;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.finmon.data.FinMonDatabase;
import com.my.finmon.data.dao.AssetDao;
import com.my.finmon.data.dao.EventDao;
import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.PortfolioValueDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.EventEntity;
import com.my.finmon.data.io.PortableAsset;
import com.my.finmon.data.io.PortableEvent;
import com.my.finmon.data.io.PortableExport;
import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.model.EventType;
import com.my.finmon.data.remote.nbu.NbuBondDto;
import com.my.finmon.data.remote.yahoo.YahooClient;
import com.my.finmon.data.remote.yahoo.YahooClient.DailyAndEvents;
import com.my.finmon.data.repository.PortfolioRepository.DividendIngest;
import com.my.finmon.data.repository.PortfolioRepository.SplitIngest;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Round-trips the user's portfolio (assets + events) to and from JSON.
 *
 * <p>Two phases on import:
 * <ol>
 *   <li>Restore — wipes the DB in a single transaction and reinserts assets + events
 *       from the file. Logical {@code (ticker, currency)} refs survive the wipe-driven
 *       id reshuffle.</li>
 *   <li>Enrichment — for every imported asset with a {@code remoteTicker} (Yahoo) or
 *       {@code isin} (NBU), pull market history + dividend / split / coupon schedule
 *       from the remote source and route through {@code PortfolioRepository.ingestStockEvents}
 *       / {@code ingestBondCoupons}. Their built-in dedup means we only synthesize income
 *       events the import itself was missing.</li>
 * </ol>
 *
 * <p>Operations dispatch to {@code viewExecutor} so they can {@code .get()} on Futures
 * scheduled against {@code ioExecutor} (network + DAO calls inside the other repos)
 * without deadlocking on the single-threaded io executor.
 */
public final class ImportExportRepository {

    private static final String TAG = "ImportExportRepository";

    /** Bumped whenever the JSON shape changes incompatibly. */
    public static final int CURRENT_VERSION = 1;

    private final FinMonDatabase db;
    private final AssetDao assetDao;
    private final EventDao eventDao;
    private final StockPriceDao stockPriceDao;
    private final ExchangeRateDao exchangeRateDao;
    private final PortfolioValueDao portfolioValueDao;
    private final PortfolioRepository portfolio;
    private final MarketDataRepository marketData;
    private final ExecutorService viewExecutor;
    private final Moshi moshi;

    public ImportExportRepository(
            @NonNull FinMonDatabase db,
            @NonNull AssetDao assetDao,
            @NonNull EventDao eventDao,
            @NonNull StockPriceDao stockPriceDao,
            @NonNull ExchangeRateDao exchangeRateDao,
            @NonNull PortfolioValueDao portfolioValueDao,
            @NonNull PortfolioRepository portfolio,
            @NonNull MarketDataRepository marketData,
            @NonNull ExecutorService viewExecutor,
            @NonNull Moshi moshi) {
        this.db = db;
        this.assetDao = assetDao;
        this.eventDao = eventDao;
        this.stockPriceDao = stockPriceDao;
        this.exchangeRateDao = exchangeRateDao;
        this.portfolioValueDao = portfolioValueDao;
        this.portfolio = portfolio;
        this.marketData = marketData;
        this.viewExecutor = viewExecutor;
        this.moshi = moshi;
    }

    @NonNull
    public Future<String> exportToJson() {
        return viewExecutor.submit(this::buildExportJson);
    }

    @NonNull
    public Future<ImportResult> importFromJson(@NonNull String json) {
        return viewExecutor.submit(() -> applyImport(json));
    }

    // ─── Export ─────────────────────────────────────────────────────────────

    private String buildExportJson() {
        List<AssetEntity> assets = assetDao.getAll();
        Map<Long, AssetEntity> byId = new HashMap<>(assets.size() * 2);
        List<PortableAsset> portableAssets = new ArrayList<>(assets.size());
        for (AssetEntity a : assets) {
            byId.put(a.id, a);
            portableAssets.add(toPortable(a));
        }

        List<EventEntity> events = eventDao.getAllChronological();
        List<PortableEvent> portableEvents = new ArrayList<>(events.size());
        for (EventEntity e : events) {
            AssetEntity asset = byId.get(e.assetId);
            if (asset == null) continue;  // FK guarantees this can't happen
            AssetEntity src = (e.incomeSourceAssetId != null) ? byId.get(e.incomeSourceAssetId) : null;
            portableEvents.add(toPortable(e, asset, src));
        }

        PortableExport export = new PortableExport();
        export.version = CURRENT_VERSION;
        export.exportedAt = LocalDateTime.now().toString();
        export.assets = portableAssets;
        export.events = portableEvents;

        JsonAdapter<PortableExport> adapter = moshi.adapter(PortableExport.class).indent("  ");
        return adapter.toJson(export);
    }

    @NonNull
    private static PortableAsset toPortable(@NonNull AssetEntity a) {
        PortableAsset p = new PortableAsset();
        p.ticker = a.ticker;
        p.currency = a.currency.name();
        p.type = a.type.name();
        p.remoteTicker = a.remoteTicker;
        p.name = a.name;
        p.isin = a.isin;
        p.bondMaturityDate = (a.bondMaturityDate != null) ? a.bondMaturityDate.toString() : null;
        p.bondInitialPrice = (a.bondInitialPrice != null) ? a.bondInitialPrice.toPlainString() : null;
        p.bondYieldPct = (a.bondYieldPct != null) ? a.bondYieldPct.toPlainString() : null;
        return p;
    }

    @NonNull
    private static PortableEvent toPortable(
            @NonNull EventEntity e, @NonNull AssetEntity asset, @Nullable AssetEntity src) {
        PortableEvent p = new PortableEvent();
        p.timestamp = e.timestamp.toString();
        p.type = e.type.name();
        p.assetTicker = asset.ticker;
        p.assetCurrency = asset.currency.name();
        p.amount = e.amount.toPlainString();
        p.price = e.price.toPlainString();
        if (src != null) {
            p.incomeSourceAssetTicker = src.ticker;
            p.incomeSourceAssetCurrency = src.currency.name();
        }
        return p;
    }

    // ─── Import ─────────────────────────────────────────────────────────────

    private ImportResult applyImport(String json) throws Exception {
        JsonAdapter<PortableExport> adapter = moshi.adapter(PortableExport.class);
        PortableExport ex = adapter.fromJson(json);
        if (ex == null) throw new IllegalArgumentException("Empty or invalid JSON");
        if (ex.version > CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Import file is version " + ex.version + " but this build only handles up to " + CURRENT_VERSION);
        }

        if (ex.assets == null) ex.assets = new ArrayList<>();
        if (ex.events == null) ex.events = new ArrayList<>();
        ensureAssetReferencesResolve(ex);

        final int[] assetCount = { 0 };
        final int[] eventCount = { 0 };

        db.runInTransaction(() -> {
            // FK order: events first (assetId → asset RESTRICT), then asset.
            // Snapshots/prices/FX are derivative — wipe alongside; enrichment refills them.
            portfolioValueDao.deleteAll();
            stockPriceDao.deleteAll();
            exchangeRateDao.deleteAll();
            eventDao.deleteAll();
            assetDao.deleteAll();

            // Re-seed cash piles. The DB's onCreate seed callback only fires when the
            // file is first created; after a row-wipe we re-create them ourselves.
            seedCashPile("CASH_USD", Currency.USD);
            seedCashPile("CASH_EUR", Currency.EUR);
            seedCashPile("CASH_UAH", Currency.UAH);

            Map<String, Long> idByKey = new HashMap<>();
            for (AssetEntity a : assetDao.getAll()) {
                idByKey.put(assetKey(a.ticker, a.currency), a.id);
            }

            for (PortableAsset pa : ex.assets) {
                if (pa == null || pa.ticker == null || pa.currency == null || pa.type == null) continue;
                AssetType type;
                try {
                    type = AssetType.valueOf(pa.type);
                } catch (IllegalArgumentException ex2) {
                    continue;
                }
                if (type == AssetType.CASH) continue;
                Currency ccy = Currency.valueOf(pa.currency);
                String key = assetKey(pa.ticker, ccy);
                if (idByKey.containsKey(key)) continue;

                AssetEntity a = new AssetEntity();
                a.ticker = pa.ticker;
                a.currency = ccy;
                a.type = type;
                a.remoteTicker = pa.remoteTicker;
                a.name = pa.name;
                a.isin = pa.isin;
                a.bondMaturityDate = parseDate(pa.bondMaturityDate);
                a.bondInitialPrice = parseBigDecimal(pa.bondInitialPrice);
                a.bondYieldPct = parseBigDecimal(pa.bondYieldPct);
                long id = assetDao.insert(a);
                idByKey.put(key, id);
                assetCount[0]++;
            }

            List<EventEntity> rows = new ArrayList<>(ex.events.size());
            for (PortableEvent pe : ex.events) {
                EventEntity e = toEntity(pe, idByKey);
                if (e != null) rows.add(e);
            }
            if (!rows.isEmpty()) {
                eventDao.insertAll(rows);
                eventCount[0] = rows.size();
            }
        });

        // Phase 2: enrichment runs OUTSIDE the transaction. Network calls + idempotent
        // ingest helpers fill in dividends / splits / coupons / prices / FX that the
        // import file was missing. Failures are logged and swallowed — a partial
        // enrichment leaves the imported events untouched, just with thinner backfill.
        int enriched = enrichAfterImport(ex);

        return new ImportResult(assetCount[0], eventCount[0], enriched);
    }

    /**
     * Pulls remote market data and routes it through the existing idempotent ingest
     * paths. Returns the count of synthesized events (dividends/splits/coupons that
     * weren't in the import file but are now in the DB).
     */
    private int enrichAfterImport(@NonNull PortableExport ex) {
        int total = 0;
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate globalEarliest = computeGlobalEarliestEventDate(ex);
        if (globalEarliest == null) return 0;

        // 1) Frankfurter FX over the whole range.
        try {
            marketData.fetchAndStoreFxRates(globalEarliest, yesterday).get();
        } catch (Exception e) {
            Log.w(TAG, "FX backfill failed", e);
        }

        // 2) Per-stock: prices + dividends + splits via Yahoo. Skip stocks without a
        //    remoteTicker — those are manual-price assets we can't enrich.
        for (PortableAsset pa : ex.assets) {
            if (pa == null || !"STOCK".equals(pa.type)) continue;
            if (pa.remoteTicker == null || pa.remoteTicker.isBlank()) continue;
            Currency ccy = parseCurrency(pa.currency);
            if (ccy == null) continue;
            AssetEntity stock = assetDao.findByTickerAndCurrency(pa.ticker, ccy);
            if (stock == null) continue;

            LocalDate from = computeAssetEarliestEventDate(ex, pa.ticker, ccy);
            if (from == null || from.isAfter(yesterday)) continue;

            try {
                DailyAndEvents r = marketData.fetchAndStoreStockPricesWithEvents(
                        pa.remoteTicker, pa.ticker, from, yesterday).get();
                List<DividendIngest> divs = new ArrayList<>(r.dividends.size());
                for (YahooClient.DividendEvent d : r.dividends) {
                    divs.add(new DividendIngest(d.at, d.perShareAmount));
                }
                List<SplitIngest> splits = new ArrayList<>(r.splits.size());
                for (YahooClient.SplitEvent s : r.splits) {
                    splits.add(new SplitIngest(s.at, s.ratio));
                }
                if (!divs.isEmpty() || !splits.isEmpty()) {
                    Integer written = portfolio.ingestStockEvents(stock.id, divs, splits).get();
                    if (written != null) total += written;
                }
            } catch (Exception e) {
                Log.w(TAG, "Yahoo enrichment failed for " + pa.ticker, e);
            }
        }

        // 3) Per-bond: NBU coupon schedule.
        for (PortableAsset pa : ex.assets) {
            if (pa == null || !"BOND".equals(pa.type)) continue;
            if (pa.isin == null || pa.isin.isBlank()) continue;
            Currency ccy = parseCurrency(pa.currency);
            if (ccy == null) continue;
            AssetEntity bond = assetDao.findByTickerAndCurrency(pa.ticker, ccy);
            if (bond == null) continue;

            try {
                NbuBondDto dto = marketData.findBondByIsin(pa.isin).get();
                if (dto == null || dto.payments == null) continue;

                List<DividendIngest> coupons = new ArrayList<>();
                for (NbuBondDto.Payment p : dto.payments) {
                    if (p == null || !"1".equals(p.pay_type)) continue;  // coupons only
                    if (p.pay_date == null || p.pay_val == null) continue;
                    LocalDate d;
                    try {
                        d = LocalDate.parse(p.pay_date);
                    } catch (Exception ex2) {
                        continue;
                    }
                    LocalDateTime at = LocalDateTime.of(d, LocalTime.of(9, 0));
                    coupons.add(new DividendIngest(at, BigDecimal.valueOf(p.pay_val)));
                }
                if (coupons.isEmpty()) continue;
                Integer written = portfolio.ingestBondCoupons(bond.id, coupons).get();
                if (written != null) total += written;
            } catch (Exception e) {
                Log.w(TAG, "NBU enrichment failed for " + pa.ticker, e);
            }
        }

        return total;
    }

    @Nullable
    private static LocalDate computeGlobalEarliestEventDate(@NonNull PortableExport ex) {
        LocalDate earliest = null;
        if (ex.events == null) return null;
        for (PortableEvent pe : ex.events) {
            if (pe == null || pe.timestamp == null) continue;
            try {
                LocalDate d = LocalDateTime.parse(pe.timestamp).toLocalDate();
                if (earliest == null || d.isBefore(earliest)) earliest = d;
            } catch (Exception ignored) {}
        }
        return earliest;
    }

    @Nullable
    private static LocalDate computeAssetEarliestEventDate(
            @NonNull PortableExport ex, @NonNull String ticker, @NonNull Currency currency) {
        LocalDate earliest = null;
        String wantedCcy = currency.name();
        if (ex.events == null) return null;
        for (PortableEvent pe : ex.events) {
            if (pe == null) continue;
            // Match either the asset itself OR a cash event sourced from this asset.
            boolean matches = ticker.equals(pe.assetTicker) && wantedCcy.equals(pe.assetCurrency);
            if (!matches && ticker.equals(pe.incomeSourceAssetTicker)
                    && wantedCcy.equals(pe.incomeSourceAssetCurrency)) {
                matches = true;
            }
            if (!matches) continue;
            try {
                LocalDate d = LocalDateTime.parse(pe.timestamp).toLocalDate();
                if (earliest == null || d.isBefore(earliest)) earliest = d;
            } catch (Exception ignored) {}
        }
        return earliest;
    }

    @Nullable
    private static Currency parseCurrency(@Nullable String s) {
        if (s == null) return null;
        try {
            return Currency.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void seedCashPile(String ticker, Currency ccy) {
        AssetEntity a = new AssetEntity();
        a.ticker = ticker;
        a.currency = ccy;
        a.type = AssetType.CASH;
        assetDao.insert(a);
    }

    private static void ensureAssetReferencesResolve(@NonNull PortableExport ex) {
        Set<String> known = new HashSet<>();
        for (Currency c : Currency.values()) known.add(assetKey("CASH_" + c.name(), c));
        if (ex.assets != null) {
            for (PortableAsset pa : ex.assets) {
                if (pa == null || pa.ticker == null || pa.currency == null) continue;
                try {
                    known.add(assetKey(pa.ticker, Currency.valueOf(pa.currency)));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (ex.events == null) return;
        for (int i = 0; i < ex.events.size(); i++) {
            PortableEvent pe = ex.events.get(i);
            if (pe == null) continue;
            if (pe.assetTicker == null || pe.assetCurrency == null) {
                throw new IllegalArgumentException("Event " + i + " missing asset reference");
            }
            try {
                Currency.valueOf(pe.assetCurrency);
            } catch (IllegalArgumentException ex2) {
                throw new IllegalArgumentException(
                        "Event " + i + " uses unsupported currency " + pe.assetCurrency);
            }
            String k = assetKey(pe.assetTicker, Currency.valueOf(pe.assetCurrency));
            if (!known.contains(k)) {
                throw new IllegalArgumentException("Event " + i + " references unknown asset " + k);
            }
            if (pe.incomeSourceAssetTicker != null && pe.incomeSourceAssetCurrency != null) {
                String s = assetKey(pe.incomeSourceAssetTicker, Currency.valueOf(pe.incomeSourceAssetCurrency));
                if (!known.contains(s)) {
                    throw new IllegalArgumentException(
                            "Event " + i + " references unknown income source " + s);
                }
            }
        }
    }

    @Nullable
    private static EventEntity toEntity(PortableEvent pe, Map<String, Long> idByKey) {
        if (pe == null) return null;
        if (pe.assetTicker == null || pe.assetCurrency == null) return null;
        Currency assetCcy = Currency.valueOf(pe.assetCurrency);
        Long assetId = idByKey.get(assetKey(pe.assetTicker, assetCcy));
        if (assetId == null) return null;

        EventEntity e = new EventEntity();
        e.timestamp = LocalDateTime.parse(pe.timestamp);
        e.type = EventType.valueOf(pe.type);
        e.assetId = assetId;
        e.amount = parseBigDecimal(pe.amount);
        e.price = parseBigDecimal(pe.price);
        if (e.amount == null) e.amount = BigDecimal.ZERO;
        if (e.price == null) e.price = BigDecimal.ZERO;

        if (pe.incomeSourceAssetTicker != null && pe.incomeSourceAssetCurrency != null) {
            Currency srcCcy = Currency.valueOf(pe.incomeSourceAssetCurrency);
            Long srcId = idByKey.get(assetKey(pe.incomeSourceAssetTicker, srcCcy));
            if (srcId != null) e.incomeSourceAssetId = srcId;
        }
        return e;
    }

    @NonNull
    private static String assetKey(@NonNull String ticker, @NonNull Currency ccy) {
        return ticker + "|" + ccy.name();
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isEmpty()) return null;
        return LocalDate.parse(s);
    }

    private static BigDecimal parseBigDecimal(String s) {
        if (s == null || s.isEmpty()) return null;
        return new BigDecimal(s);
    }

    public static final class ImportResult {
        public final int assetsImported;
        public final int eventsImported;
        public final int eventsEnriched;

        public ImportResult(int assetsImported, int eventsImported, int eventsEnriched) {
            this.assetsImported = assetsImported;
            this.eventsImported = eventsImported;
            this.eventsEnriched = eventsEnriched;
        }
    }
}
