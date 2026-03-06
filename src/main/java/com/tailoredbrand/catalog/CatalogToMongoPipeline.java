package com.tailoredbrand.catalog;

import com.tailoredbrand.catalog.io.CatalogExtractorFn;
import com.tailoredbrand.catalog.io.MongoUpsertFn;
import com.tailoredbrand.config.AppConfig;
import com.tailoredbrand.io.CsvParserFn;
import com.tailoredbrand.model.ProductCsvRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTagList;

/**
 * Standalone Apache Beam batch pipeline that:
 *
 * <ol>
 *   <li>Reads {@code TBUniverseProducts.csv} (pipe-delimited) as raw text lines.</li>
 *   <li>Parses each line into a flat {@link ProductCsvRecord} — the "universe JSON" row
 *       with every field from the CSV header mapped 1-to-1.</li>
 *   <li>Passes every record through {@link CatalogExtractorFn} which segregates it into
 *       three typed documents on separate side outputs:
 *       <ul>
 *         <li><b>Product</b>  – style-level attributes, keyed by {@code ParentProductCode}</li>
 *         <li><b>Variant</b>  – colour-level attributes, keyed by {@code ParentProductCode_ProductColorCode}</li>
 *         <li><b>SKU</b>      – item/size-level attributes, keyed by {@code ItemCode}</li>
 *       </ul>
 *   </li>
 *   <li>Groups each branch by its key to deduplicate rows that share the same identity
 *       (e.g. many size rows for the same product).</li>
 *   <li>Takes the first document in each group (style/colour-level fields are identical
 *       across sibling rows) and upserts it into the corresponding MongoDB collection
 *       via {@link MongoUpsertFn} using {@code replaceOne(upsert=true)}.</li>
 * </ol>
 *
 * <p>This pipeline is entirely self-contained and does not share any state with the
 * existing {@code CsvIngestionPipeline} or {@code ProductIngestionProcessor}.
 */
@Slf4j
public class CatalogToMongoPipeline {

    public static Pipeline build(AppConfig config, PipelineOptions options) {

        AppConfig.MongoDB   mongo = config.getMongodb();
        String              csv   = config.getCsv().getInputFile();

        log.info("[CATALOG PIPELINE] Building | csv={} db={} products={} variants={} skus={}",
                csv, mongo.getDatabase(),
                mongo.getProductsCollection(), mongo.getVariantsCollection(), mongo.getSkusCollection());

        Pipeline pipeline = Pipeline.create(options);

        // ── Step 1 – read + parse CSV ─────────────────────────────────────────
        PCollectionTuple parsed = pipeline
                .apply("ReadUniverseCsv",
                        TextIO.read().from(csv))
                .apply("ParseCsvLines",
                        ParDo.of(new CsvParserFn())
                             .withOutputTags(CsvParserFn.VALID,
                                     TupleTagList.of(CsvParserFn.DEAD_LETTER)));

        // Log dead-lettered rows (bad lines) for visibility.
        parsed.get(CsvParserFn.DEAD_LETTER)
                .apply("LogDeadLetters", ParDo.of(new DeadLetterLoggerFn()));

        // ── Step 2 – extract Product / Variant / SKU documents ────────────────
        PCollectionTuple extracted = parsed.get(CsvParserFn.VALID)
                .apply("ExtractCatalogDocs",
                        ParDo.of(new CatalogExtractorFn())
                             .withOutputTags(
                                     CatalogExtractorFn.PRODUCT_TAG,
                                     TupleTagList.of(CatalogExtractorFn.VARIANT_TAG)
                                                 .and(CatalogExtractorFn.SKU_TAG)));

        // ── Step 3 – dedup + upsert Products ─────────────────────────────────
        extracted.get(CatalogExtractorFn.PRODUCT_TAG)
                .apply("GroupProducts",  GroupByKey.create())
                .apply("DedupeProducts", ParDo.of(new TakeFirstFn()))
                .apply("UpsertProducts", ParDo.of(
                        new MongoUpsertFn(mongo.getUri(), mongo.getDatabase(),
                                mongo.getProductsCollection())));

        // ── Step 4 – dedup + upsert Variants ─────────────────────────────────
        extracted.get(CatalogExtractorFn.VARIANT_TAG)
                .apply("GroupVariants",  GroupByKey.create())
                .apply("DedupeVariants", ParDo.of(new TakeFirstFn()))
                .apply("UpsertVariants", ParDo.of(
                        new MongoUpsertFn(mongo.getUri(), mongo.getDatabase(),
                                mongo.getVariantsCollection())));

        // ── Step 5 – dedup + upsert SKUs ─────────────────────────────────────
        extracted.get(CatalogExtractorFn.SKU_TAG)
                .apply("GroupSkus",  GroupByKey.create())
                .apply("DedupeSkus", ParDo.of(new TakeFirstFn()))
                .apply("UpsertSkus", ParDo.of(
                        new MongoUpsertFn(mongo.getUri(), mongo.getDatabase(),
                                mongo.getSkusCollection())));

        return pipeline;
    }

    // ── Inline DoFns ──────────────────────────────────────────────────────────

    /**
     * Accepts a grouped {@code KV<String, Iterable<String>>} and emits only the
     * first JSON string in the group, effectively deduplicating by key.
     *
     * <p>For Products and Variants the style/colour-level fields are identical
     * across sibling rows, so any representative row is valid.  For SKUs every
     * {@code ItemCode} is already unique, so the group always has exactly one element.
     */
    static class TakeFirstFn extends DoFn<KV<String, Iterable<String>>, String> {
        @ProcessElement
        public void processElement(ProcessContext ctx) {
            Iterable<String> values = ctx.element().getValue();
            if (values != null) {
                for (String v : values) {
                    if (v != null) {
                        ctx.output(v);
                        return;   // first wins
                    }
                }
            }
        }
    }

    static class DeadLetterLoggerFn extends DoFn<String, Void> {
        @ProcessElement
        public void processElement(ProcessContext ctx) {
            String line = ctx.element();
            log.error("[CATALOG PIPELINE] Dead-letter row: {}",
                    line.length() > 200 ? line.substring(0, 200) + "…" : line);
        }
    }
}
