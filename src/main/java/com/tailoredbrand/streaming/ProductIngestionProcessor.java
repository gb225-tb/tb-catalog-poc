package com.tailoredbrand.streaming;

import com.google.auth.oauth2.GoogleCredentials;
import com.tailoredbrand.config.AppConfig;
import com.tailoredbrand.config.YamlConfigLoader;
import com.tailoredbrand.pipeline.CsvIngestionPipeline;
import com.tailoredbrand.pipeline.Subscriber;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;

import java.io.IOException;
import java.util.Collections;

/**
 * Entry point.  Selects the pipeline mode from the YAML config:
 * <ul>
 *   <li><b>csv</b> – batch: reads TBUniverseProducts CSV → parses → upserts via Commerce API</li>
 *   <li><b>streaming</b> – reads Pub/Sub → writes to MongoDB</li>
 * </ul>
 *
 * Pass {@code --mode=csv} or {@code --mode=streaming} as a CLI arg to override the default.
 */
@Slf4j
public class ProductIngestionProcessor {

    public static void main(String[] args) throws IOException {

        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        log.info("Loaded credentials: {}", credentials.getClass().getSimpleName());

        AppConfig config = YamlConfigLoader.load("config/pipeline.yaml");

        PipelineOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().create();
        options.as(GcpOptions.class).setProject(config.getGcp().getProjectId());
        options.as(PubsubOptions.class).setProject(config.getGcp().getProjectId());
        options.as(StreamingOptions.class).setStreaming(config.getPipeline().isStreaming());

        if (config.getPipeline().getRunner().equalsIgnoreCase("DataflowRunner")) {
            DataflowPipelineOptions df = options.as(DataflowPipelineOptions.class);
            df.setProject(config.getGcp().getProjectId());
            df.setRegion(config.getGcp().getRegion());
            df.setTempLocation(config.getGcp().getTempLocation());
            df.setServiceAccount(config.getGcp().getServiceAccount());
            df.setJobName("tb-catalog-product-ingestion");
            df.setRunner(org.apache.beam.runners.dataflow.DataflowRunner.class);
            df.setExperiments(Collections.singletonList("enable_preflight_validation=false"));
            options = df;
        } else {
            options.setRunner(org.apache.beam.runners.direct.DirectRunner.class);
        }

        String mode = resolveMode(args, config);
        log.info("Starting pipeline mode={} project={}", mode, config.getGcp().getProjectId());

        Pipeline pipeline = mode.equalsIgnoreCase("csv")
                ? CsvIngestionPipeline.build(config, options)
                : Subscriber.build(config, options);

        pipeline.run().waitUntilFinish();
    }

    private static String resolveMode(String[] args, AppConfig config) {
        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                return arg.substring("--mode=".length());
            }
        }
        return config.getCsv() != null && config.getCsv().getInputFile() != null
                ? "csv"
                : "streaming";
    }
}
