package com.tailoredbrand.pubsub;

import com.google.gson.Gson;
import com.tailoredbrand.model.TailoredBrandEventModels;
import com.tailoredbrand.utils.GsonConfig;
import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.SubscriptionName;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class PubSubSubscriber {

    private final PubSubEventSink eventSink;;

    @Value("${gcp.project-id}")
    private String projectId;

    @Value("${gcp.pubsub.subscription}")
    private String subscription;

    public PubSubSubscriber(PubSubEventSink eventSink) {
        this.eventSink = eventSink;
    }

    @PostConstruct
    public void startSubscriber() {

        SubscriptionName subscriptionName =
                SubscriptionName.of(projectId, subscription);

        MessageReceiver receiver = (message, consumer) -> {
            try {
                String payload = new String(message.getData().toByteArray(), StandardCharsets.UTF_8);


                log.info("Received messageId={}, payload={}, attributes={}",
                        message.getMessageId(),
                        payload,
                        message.getAttributes());
                Gson gson = GsonConfig.gson();

                TailoredBrandEventModels event =
                        gson.fromJson(payload, TailoredBrandEventModels.class);

                log.info("Received TailoredBrand eventType={}",
                        event);

                // 🔥 publish to event stream buffer
                eventSink.publish(payload);

                consumer.ack();
            } catch (Exception e) {
                log.error("Message processing failed", e);
                consumer.nack();
            }
        };

        Subscriber subscriber =
                Subscriber.newBuilder(String.valueOf(subscriptionName), receiver).build();

        subscriber.startAsync().awaitRunning();
        log.info("Subscriber started for {}", subscription);
    }
}


