package com.tailoredbrand.config;

import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.sdk.options.Validation.Required;

public interface PipelineConfig extends PipelineOptions, StreamingOptions {

    // Pub/Sub input topic
    @Required
    String getInputTopic();
    void setInputTopic(String inputTopic);

    // MongoDB configurations
    @Required
    String getMongoUri();
    void setMongoUri(String mongoUri);

    @Required
    String getMongoDatabase();
    void setMongoDatabase(String mongoDatabase);

    @Required
    String getMongoCollection();
    void setMongoCollection(String mongoCollection);
}
