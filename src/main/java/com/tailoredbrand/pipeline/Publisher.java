package com.tailoredbrand.pipeline;

import com.tailoredbrand.config.AppConfig;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.DoFn;

public class Publisher {

    // Example DoFn to transform or generate messages before publishing
    static class PrepareMessageFn extends DoFn<String, String> {
        @ProcessElement
        public void processElement(ProcessContext c) {
            String message = c.element();
            // Example transformation: uppercase the message
            c.output(message.toUpperCase());
        }
    }

    public static Pipeline build(AppConfig config, PipelineOptions options) {

        Pipeline pipeline = Pipeline.create(options);

        pipeline.apply("ReadFromSource", /* Replace with your source PCollection or transform */
                        PubsubIO.readStrings().fromSubscription(config.gcp.pubsub.inputSubscription))
                .apply("PrepareMessages", ParDo.of(new PrepareMessageFn()))
                .apply("WriteToPubSub", PubsubIO.writeStrings()
                        .to(config.gcp.pubsub.outboundTopic));

        return pipeline;
    }
}


