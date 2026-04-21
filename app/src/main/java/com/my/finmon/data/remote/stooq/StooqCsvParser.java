package com.my.finmon.data.remote.stooq;

import androidx.annotation.NonNull;

import com.my.finmon.data.entity.StockPriceEntity;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the Stooq daily-EOD CSV body into {@link StockPriceEntity} rows.
 *
 * Expected header: {@code Date,Open,High,Low,Close,Volume}. Open/High/Low/Volume are
 * discarded (the entity only stores close). Date format: {@code yyyy-MM-dd}.
 *
 * Two non-CSV response shapes to handle:
 *   - "Get your apikey:\n..." — key missing or revoked; thrown as IOException so the
 *     caller sees a clear reason rather than a mysterious parse error.
 *   - "No data" — valid empty result for an unknown ticker or range with no trading days.
 */
final class StooqCsvParser {

    private StooqCsvParser() {}

    @NonNull
    static List<StockPriceEntity> parse(@NonNull String csvBody, @NonNull String ticker)
            throws IOException {

        if (csvBody.isBlank()) return new ArrayList<>();
        if (csvBody.startsWith("Get your apikey")) {
            throw new IOException(
                    "Stooq API key missing or rejected — regenerate via captcha and update local.properties");
        }
        if (csvBody.startsWith("No data")) return new ArrayList<>();

        String[] lines = csvBody.split("\\r?\\n");
        if (lines.length == 0) return new ArrayList<>();

        String header = lines[0].trim();
        if (!header.startsWith("Date,") || !header.contains(",Close")) {
            throw new IOException("Unexpected Stooq CSV header: " + header);
        }

        String[] cols = header.split(",", -1);
        int dateIdx = -1;
        int closeIdx = -1;
        for (int i = 0; i < cols.length; i++) {
            if ("Date".equalsIgnoreCase(cols[i])) dateIdx = i;
            else if ("Close".equalsIgnoreCase(cols[i])) closeIdx = i;
        }
        if (dateIdx < 0 || closeIdx < 0) {
            throw new IOException("Stooq CSV missing Date or Close column: " + header);
        }

        List<StockPriceEntity> out = new ArrayList<>(Math.max(0, lines.length - 1));
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] fields = line.split(",", -1);
            if (fields.length <= Math.max(dateIdx, closeIdx)) {
                throw new IOException("Stooq CSV row too short: " + line);
            }
            StockPriceEntity row = new StockPriceEntity();
            row.ticker = ticker;
            row.date = LocalDate.parse(fields[dateIdx]);
            row.closePrice = new BigDecimal(fields[closeIdx]);
            out.add(row);
        }
        return out;
    }
}
