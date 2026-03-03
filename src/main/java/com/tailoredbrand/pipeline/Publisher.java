package com.tailoredbrand.pipeline;

import com.tailoredbrand.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;

@Slf4j
public class Publisher {

    static class PrepareMessageFn extends DoFn<String, String> {
        @ProcessElement
        public void processElement(ProcessContext c) {
            c.output(c.element().toUpperCase());
        }
    }

    public static Pipeline build(AppConfig config, PipelineOptions options) {
        Pipeline pipeline = Pipeline.create(options);

        pipeline
                .apply("ReadFromSubscription",
                        PubsubIO.readStrings().fromSubscription(
                                config.getGcp().getPubsub().getInputSubscription()))
                .apply("PrepareMessages", ParDo.of(new PrepareMessageFn()))
                .apply("WriteToPubSub",
                        PubsubIO.writeStrings().to(config.getGcp().getPubsub().getOutboundTopic()));

        return pipeline;
    }
}
