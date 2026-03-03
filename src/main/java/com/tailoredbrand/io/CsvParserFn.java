package com.tailoredbrand.io;

import com.tailoredbrand.model.ProductCsvRecord;
import com.tailoredbrand.utils.ProductCsvParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.TupleTag;

/**
 * Beam DoFn that parses pipe-delimited CSV lines into {@link ProductCsvRecord}.
 *
 * <ul>
 *   <li>Header and blank lines are silently dropped.</li>
 *   <li>Lines that fail parsing are routed to {@link #DEAD_LETTER} so the pipeline
 *       remains fault-tolerant and never blocks on a single bad row.</li>
 *   <li>Successfully parsed rows are logged at INFO with a one-line summary.</li>
 * </ul>
 */
@Slf4j
public class CsvParserFn extends DoFn<String, ProductCsvRecord> {

    public static final TupleTag<ProductCsvRecord> VALID       = new TupleTag<>() {};
    public static final TupleTag<String>           DEAD_LETTER = new TupleTag<>() {};

    @ProcessElement
    public void processElement(ProcessContext ctx, MultiOutputReceiver out) {
        String line = ctx.element();

        if (line == null || line.isBlank()) {
            return;
        }

        if (ProductCsvParser.isHeaderLine(line)) {
            log.info("[CSV PARSE] Header row detected — column schema accepted, data rows follow.");
            return;
        }

        try {
            ProductCsvRecord record = ProductCsvParser.parseLine(line);
            logParsedRecord(record);
            out.get(VALID).output(record);
        } catch (Exception ex) {
            log.warn("[CSV PARSE] ✗ Dead-lettering bad row | error={} | preview={}",
                    ex.getMessage(), truncate(line));
            out.get(DEAD_LETTER).output(line);
        }
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private void logParsedRecord(ProductCsvRecord r) {
        log.info("[CSV PARSE] ✓ itemCode={} | parentCode={} | size={} | color={} | msrp=${} | fit={} | desc={}",
                r.itemCode(),
                r.parentProductCode(),
                r.sizeDescription() != null ? r.sizeDescription() : r.sizeCode(),
                r.colorDesc(),
                r.msrp() != null ? r.msrp() : "N/A",
                r.fit(),
                truncate(r.webLongDesc()));
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
