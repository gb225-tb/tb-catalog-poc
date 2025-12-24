package com.tailoredbrand.controller;

import com.tailoredbrand.model.PublishResponse;
import com.tailoredbrand.model.TailoredBrandEventModels;
import com.tailoredbrand.pubsub.PubSubEventSink;
import com.tailoredbrand.service.PubSubPublisherService;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;


@RestController
@RequestMapping("/api/pubsub")
@Slf4j
public class PubSubController {

    @Autowired
    private final PubSubPublisherService publisherService;

    @Autowired
    private final PubSubEventSink eventSink;

    public PubSubController(PubSubPublisherService publisherService, PubSubEventSink eventSink) {
        this.publisherService = publisherService;
        this.eventSink = eventSink;
    }


    @PostMapping("/publish")
    public ResponseEntity<PublishResponse> publishMessage(
            @RequestBody TailoredBrandEventModels.TailoredBrandEvent event) {

        String messageId = publisherService.publish(event);

        return ResponseEntity.ok(
                new PublishResponse("SUCCESS", messageId)
        );
    }



    @GetMapping(
            value = "/subscribe",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> streamEvents() {
        return eventSink.flux()
                .map(event -> event);
    }


    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Pub/Sub connectivity OK");
    }
}

