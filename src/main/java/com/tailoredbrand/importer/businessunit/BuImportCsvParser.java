package com.tailoredbrand.importer.businessunit;

import com.tailoredbrand.importer.product.ProductImportCsvParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses the Business Unit import CSV (multi-row per BU) into a list of
 * {@link BuImportGroup}s.
 *
 * <h3>Grouping algorithm</h3>
 * <ol>
 *   <li>Skip the header row.</li>
 *   <li>Parse each data row into a {@link BuImportRecord}.</li>
 *   <li>Rows with a non-blank {@code key} column → start a new BU group.</li>
 *   <li>Blank-key rows with a non-blank associate role key → associate
 *       continuation row for the current BU.</li>
 *   <li>Blank-key rows with a non-blank address key → address continuation
 *       row for the current BU.</li>
 *   <li>All-blank rows are silently skipped.</li>
 * </ol>
 *
 * <h3>Import ordering</h3>
 * The returned list is sorted so that <b>Company</b> BUs always come before
 * <b>Division</b> BUs.  This satisfies the CT constraint that a Division's
 * parent Company must already exist at import time.
 */
@Component
@Slf4j
public class BuImportCsvParser {

    public List<BuImportGroup> parse(InputStream inputStream) throws IOException {
        List<BuImportGroup> groups = new ArrayList<>();

        BuImportRecord currentHeader    = null;
        List<BuImportRecord> associates = null;
        List<BuImportRecord> addresses  = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            boolean headerSkipped = false;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;

                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }

                String[] cols = ProductImportCsvParser.splitCsvLine(line);
                BuImportRecord row = BuImportRecord.fromCsvColumns(cols);

                if (row.isNewBuRow()) {
                    flushGroup(groups, currentHeader, associates, addresses);
                    currentHeader = row;
                    associates    = new ArrayList<>();
                    addresses     = new ArrayList<>();
                    log.debug("[BU CSV] New BU group | key={} | type={} | line={}",
                            row.key(), row.unitType(), lineNumber);

                } else if (row.isAssociateContinuationRow()) {
                    if (associates == null) {
                        log.warn("[BU CSV] Orphan associate row at line {} — skipping", lineNumber);
                        continue;
                    }
                    associates.add(row);

                } else if (row.isAddressContinuationRow()) {
                    if (addresses == null) {
                        log.warn("[BU CSV] Orphan address row at line {} — skipping", lineNumber);
                        continue;
                    }
                    addresses.add(row);

                } else {
                    log.debug("[BU CSV] Skipping unclassifiable row at line {}", lineNumber);
                }
            }

            flushGroup(groups, currentHeader, associates, addresses);
        }

        // Companies before Divisions so parent BUs are created first
        groups.sort(Comparator.comparingInt(g -> g.isDivision() ? 1 : 0));

        log.info("[BU CSV] Parsed {} BU group(s) ({} companies, {} divisions)",
                groups.size(),
                groups.stream().filter(g -> !g.isDivision()).count(),
                groups.stream().filter(BuImportGroup::isDivision).count());

        return groups;
    }

    // ── Flush helper ──────────────────────────────────────────────────────────

    private void flushGroup(List<BuImportGroup> groups,
                             BuImportRecord header,
                             List<BuImportRecord> associates,
                             List<BuImportRecord> addresses) {
        if (header == null) return;
        groups.add(new BuImportGroup(
                header,
                associates  != null ? List.copyOf(associates)  : List.of(),
                addresses   != null ? List.copyOf(addresses)   : List.of()
        ));
    }
}
