package com.tailoredbrand.pubsub;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class PubSubEventSink {

    private final Sinks.Many<String> sink =
            Sinks.many().multicast().onBackpressureBuffer();

    public void publish(String event) {
        sink.tryEmitNext(event);
    }

    public Flux<String> flux() {
        return sink.asFlux();
    }
}

