package com.tailoredbrand.pipeline;

import com.tailoredbrand.config.AppConfig;
import com.tailoredbrand.io.MongoWriterFn;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.ParDo;

public class Subscriber {

    public static Pipeline build(AppConfig config, PipelineOptions options) {
        Pipeline pipeline = Pipeline.create(options);

        MongoWriterFn writerFn = new MongoWriterFn(
                config.getMongodb().getUri(),
                config.getMongodb().getDatabase(),
                config.getMongodb().getCollection()
        );

        pipeline
                .apply("ReadFromPubSub",
                        PubsubIO.readStrings().fromTopic(config.getGcp().getPubsub().getInboundTopic()))
                .apply("WriteToMongo", ParDo.of(writerFn));

        return pipeline;
    }
}
