package com.tailoredbrand.importer.product;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the CT-format product import CSV (multi-row per product) into a flat
 * list of {@link ProductImportGroup}s.
 *
 * <h3>Grouping algorithm</h3>
 * <ol>
 *   <li>Skip the header row.</li>
 *   <li>For each data row, parse into a {@link ProductImportRecord}.</li>
 *   <li>If the row's {@code key} column is non-blank → start a new group.</li>
 *   <li>Otherwise → append to the current group (continuation row).</li>
 * </ol>
 *
 * <p>Empty lines are silently skipped.</p>
 */
@Component
@Slf4j
public class ProductImportCsvParser {

    /**
     * Parses the given input stream and returns one {@link ProductImportGroup}
     * per distinct product key found in the file.
     *
     * @param inputStream raw bytes of the uploaded CSV
     * @return ordered list of product groups (preserves CSV row order)
     * @throws IOException if reading fails
     */
    public List<ProductImportGroup> parse(InputStream inputStream) throws IOException {
        List<ProductImportGroup> groups = new ArrayList<>();
        List<ProductImportRecord> currentRows = null;
        ProductImportRecord currentHeader = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            boolean headerSkipped = false;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;

                if (!headerSkipped) {
                    headerSkipped = true;   // first line = column header
                    continue;
                }

                String[] cols = splitCsvLine(line);
                ProductImportRecord row = ProductImportRecord.fromCsvColumns(cols);

                if (row.key() != null && !row.key().isBlank()) {
                    // Flush the previous group
                    if (currentHeader != null) {
                        groups.add(new ProductImportGroup(currentHeader, List.copyOf(currentRows)));
                    }
                    currentHeader = row;
                    currentRows   = new ArrayList<>();
                    currentRows.add(row);
                    log.debug("[PRODUCT CSV] New product group | key={} | line={}", row.key(), lineNumber);
                } else {
                    if (currentRows == null) {
                        log.warn("[PRODUCT CSV] Orphan continuation row at line {} — no product key seen yet; skipping", lineNumber);
                        continue;
                    }
                    currentRows.add(row);
                }
            }

            // Flush last group
            if (currentHeader != null) {
                groups.add(new ProductImportGroup(currentHeader, List.copyOf(currentRows)));
            }
        }

        log.info("[PRODUCT CSV] Parsed {} product group(s)", groups.size());
        return groups;
    }

    /**
     * Minimal CSV line splitter that handles comma-separated values.
     * Values may be quoted (double-quoted); inner escaped quotes ({@code ""}) are
     * handled correctly.  Does NOT support multi-line quoted fields.
     */
    public static String[] splitCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        tokens.add(current.toString());
        return tokens.toArray(new String[0]);
    }
}
