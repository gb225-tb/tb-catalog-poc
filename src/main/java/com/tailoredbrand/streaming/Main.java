package com.tailoredbrand.streaming;

import com.google.auth.oauth2.GoogleCredentials;
import com.tailoredbrand.config.AppConfig;
import com.tailoredbrand.config.YamlConfigLoader;
import com.tailoredbrand.pipeline.Publisher;
import com.tailoredbrand.pipeline.Subscriber;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubOptions;

import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException {

        // Load credentials
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        System.out.println("Loaded credentials: " + credentials);

        // Load YAML configuration
        AppConfig config = YamlConfigLoader.load("config/pipeline.yaml");

        // Create base PipelineOptions
        PipelineOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().create();

        // GCP / Pub/Sub configuration
        options.as(GcpOptions.class).setProject(config.gcp.projectId);
        options.as(PubsubOptions.class).setProject(config.gcp.projectId);
        options.as(StreamingOptions.class).setStreaming(config.pipeline.streaming);

        System.out.println("Loaded project = " + config.gcp.projectId);

        // Runner configuration
        if (config.pipeline.runner.equalsIgnoreCase("DataflowRunner")) {
            DataflowPipelineOptions dfOptions = options.as(DataflowPipelineOptions.class);
            dfOptions.setProject(config.gcp.projectId);
            dfOptions.setRegion(config.gcp.region);
            dfOptions.setTempLocation(config.gcp.tempLocation);
            dfOptions.setServiceAccount(config.gcp.serviceAccount);
            dfOptions.setJobName("tb-catalog-data-inbound-processing");
            dfOptions.setRunner(org.apache.beam.runners.dataflow.DataflowRunner.class);
            dfOptions.setExperiments(java.util.Collections.singletonList("enable_preflight_validation=false"));
            options = dfOptions;
        } else {
            options.setRunner(org.apache.beam.runners.direct.DirectRunner.class);
        }

        // Build and run Subscriber pipeline
        Pipeline subscriberPipeline = Subscriber.build(config, options);
        subscriberPipeline.run().waitUntilFinish();
    }
}
