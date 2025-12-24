package com.tailoredbrand.service;

import java.io.IOException;

import com.tailoredbrand.model.TailoredBrandEventModels;
import com.tailoredbrand.utils.GsonConfig;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import com.google.gson.Gson;

@Service
@Slf4j
public class PubSubPublisherService {

    private final Publisher publisher;

    public PubSubPublisherService(
            @Value("${gcp.pubsub.topic}") String topic,
            @Value("${gcp.project-id}") String projectId) throws IOException {

        TopicName topicName = TopicName.of(projectId, topic);
        this.publisher = Publisher.newBuilder(topicName).build();
    }

    public String publish(TailoredBrandEventModels.TailoredBrandEvent event) {

        Gson gson = GsonConfig.gson();
        String jsonPayload = gson.toJson(event);

        PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(jsonPayload))
                .putAttributes(
                        "eventType",
                        event.eventMetadata().eventType()
                )
                .putAttributes("source", "tailored-brands-catalog")
                .build();

        try {
            String messageId = publisher.publish(message).get();
            log.info("Published messageId={}, payload={}", messageId, jsonPayload);
            return messageId;
        } catch (Exception e) {
            log.error("Failed to publish message", e);
            throw new RuntimeException("Publish failed", e);
        }
    }
}

