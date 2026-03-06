package com.tailoredbrand.catalog;

import com.tailoredbrand.config.AppConfig;
import com.tailoredbrand.config.YamlConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;

/**
 * Standalone entry point for the <em>Catalog-to-MongoDB</em> Apache Beam pipeline.
 *
 * <p>Reads its configuration from the same {@code config/pipeline.yaml} as the rest of
 * the application (the {@code mongodb.*} block plus {@code csv.inputFile} and
 * {@code pipeline.runner}).  No existing classes are modified.
 *
 * <h3>Run locally (DirectRunner)</h3>
 * <pre>
 *   mvn compile exec:java \
 *     -Dexec.mainClass=com.tailoredbrand.catalog.CatalogProcessor
 * </pre>
 *
 * <h3>Run on Dataflow</h3>
 * <pre>
 *   mvn compile exec:java \
 *     -Dexec.mainClass=com.tailoredbrand.catalog.CatalogProcessor \
 *     -Dexec.args="--runner=DataflowRunner --project=... --region=... --tempLocation=..."
 * </pre>
 */
@Slf4j
public class CatalogProcessor {

    private static final String CONFIG_FILE = "config/pipeline.yaml";

    public static void main(String[] args) {
        AppConfig config = YamlConfigLoader.load(CONFIG_FILE);

        String runner = config.getPipeline().getRunner();
        log.info("[CATALOG] Starting Catalog-to-MongoDB pipeline | runner={} csv={}",
                runner, config.getCsv().getInputFile());

        // Do NOT call withValidation() here – it triggers GCP option checks
        // even when running locally with DirectRunner.
        PipelineOptions options = PipelineOptionsFactory.fromArgs(args).create();

        if ("DataflowRunner".equalsIgnoreCase(runner)) {
            org.apache.beam.runners.dataflow.options.DataflowPipelineOptions df =
                    options.as(org.apache.beam.runners.dataflow.options.DataflowPipelineOptions.class);
            df.setProject(config.getGcp().getProjectId());
            df.setRegion(config.getGcp().getRegion());
            df.setTempLocation(config.getGcp().getTempLocation());
            df.setServiceAccount(config.getGcp().getServiceAccount());
            df.setJobName("tb-catalog-to-mongo");
            df.setRunner(org.apache.beam.runners.dataflow.DataflowRunner.class);
            options = df;
        } else {
            // DirectRunner – no GCP credentials required for local execution.
            options.setRunner(org.apache.beam.runners.direct.DirectRunner.class);
        }

        Pipeline pipeline = CatalogToMongoPipeline.build(config, options);
        pipeline.run().waitUntilFinish();

        log.info("[CATALOG] Pipeline finished.");
    }
}
