package com.tailoredbrand.pipeline;

import com.tailoredbrand.commerce.CommerceToolsSettings;
import com.tailoredbrand.config.AppConfig;
import com.tailoredbrand.io.CommerceProductGroupedUpsertFn;
import com.tailoredbrand.io.CommerceProductUpsertFn;
import com.tailoredbrand.io.CsvParserFn;
import com.tailoredbrand.model.ProductApiResult;
import com.tailoredbrand.model.ProductCsvRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTagList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Batch pipeline:
 *
 * <pre>
 *   TextIO.read(csv)
 *     → CsvParserFn           – parse rows; bad rows → dead-letter side output
 *     → Reshuffle             – distribute work evenly across workers
 *     → CommerceProductUpsertFn – GET → create or addVariant via CommerceTools WebClient
 *     → ResultLoggerFn        – structured success / failure logging
 * </pre>
 */
@Slf4j
public class CsvIngestionPipeline {

    public static Pipeline build(AppConfig config, PipelineOptions options) {
        Pipeline pipeline = Pipeline.create(options);

        CommerceProductUpsertFn upsertFn = new CommerceProductUpsertFn(
                buildSettings(config.getCommerce())
        );

        PCollectionTuple parsed = pipeline
                .apply("ReadCsvFile", TextIO.read().from(config.getCsv().getInputFile()))
                .apply("ParseCsvLines", ParDo
                        .of(new CsvParserFn())
                        .withOutputTags(CsvParserFn.VALID, TupleTagList.of(CsvParserFn.DEAD_LETTER)));

        // Group by parentProductCode for single product per parent
        parsed.get(CsvParserFn.VALID)
                .apply("ToKV", ParDo.of(new DoFn<ProductCsvRecord, KV<String, ProductCsvRecord>>() {
                    @ProcessElement
                    public void processElement(ProcessContext ctx) {
                        ProductCsvRecord rec = ctx.element();
                        ctx.output(KV.of(rec.parentProductCode(), rec));
                    }
                }))
                .apply("GroupByParent", GroupByKey.create())
                .apply("ToGroupedList", ParDo.of(new DoFn<KV<String, Iterable<ProductCsvRecord>>, List<ProductCsvRecord>>() {
                    @ProcessElement
                    public void processElement(ProcessContext ctx) {
                        List<ProductCsvRecord> group = new ArrayList<>();
                        ctx.element().getValue().forEach(group::add);
                        ctx.output(group);
                    }
                }))
                .apply("UpsertGroupedProduct", ParDo.of(new CommerceProductGroupedUpsertFn(buildSettings(config.getCommerce()))))
                .apply("LogResults", ParDo.of(new ResultLoggerFn()));

        parsed.get(CsvParserFn.DEAD_LETTER)
                .apply("LogDeadLetters", ParDo.of(new DeadLetterLoggerFn()));

        return pipeline;
    }

    // ── Settings factory ─────────────────────────────────────────────────────

    static CommerceToolsSettings buildSettings(AppConfig.CommerceApi cfg) {
        Objects.requireNonNull(cfg, "commerce config block is required in pipeline.yaml");
        return CommerceToolsSettings.builder()
                .authUrl(Objects.requireNonNull(cfg.getAuthUrl(),           "commerce.authUrl"))
                .clientCredentials(Objects.requireNonNull(cfg.getClientCredentials(), "commerce.clientCredentials"))
                .scope(Objects.requireNonNull(cfg.getScope(),               "commerce.scope"))
                .apiUrl(Objects.requireNonNull(cfg.getApiUrl(),             "commerce.apiUrl"))
                .projectKey(Objects.requireNonNull(cfg.getProjectKey(),     "commerce.projectKey"))
                .productTypeKey(Objects.requireNonNull(cfg.getProductTypeKey(), "commerce.productTypeKey"))
                .secondaryProductTypeKey(cfg.getSecondaryProductTypeKey())
                .primaryProductTypeDivisions(cfg.getPrimaryProductTypeDivisions())
                .connectTimeoutMs(cfg.getConnectTimeoutMs() != null ? cfg.getConnectTimeoutMs() : 5_000)
                .readTimeoutMs(cfg.getReadTimeoutMs()     != null ? cfg.getReadTimeoutMs()     : 15_000)
                .maxRetries(cfg.getMaxRetries()           != null ? cfg.getMaxRetries()         : 3)
                .backoffMs(cfg.getBackoffMs()             != null ? cfg.getBackoffMs()          : 500L)
                .build();
    }

    // ── Inline DoFns ─────────────────────────────────────────────────────────

    static class ResultLoggerFn extends DoFn<ProductApiResult, Void> {
        @ProcessElement
        public void processElement(ProcessContext ctx) {
            ProductApiResult r = ctx.element();
            if (r.success()) {
                log.info("SUCCESS op={} itemCode={} status={}", r.operation(), r.itemCode(), r.statusCode());
            } else {
                log.warn("FAILURE op={} itemCode={} status={} msg={}", r.operation(), r.itemCode(), r.statusCode(), r.message());
            }
        }
    }

    static class DeadLetterLoggerFn extends DoFn<String, Void> {
        @ProcessElement
        public void processElement(ProcessContext ctx) {
            String line = ctx.element();
            log.error("DEAD_LETTER unparseable CSV row: {}",
                    line.length() > 200 ? line.substring(0, 200) + "…" : line);
        }
    }
}
