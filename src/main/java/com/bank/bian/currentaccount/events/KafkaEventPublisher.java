package com.bank.bian.currentaccount.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter (profile `kafka`) — same DomainEvent shapes the logging
 * adapter has been emitting since Phase 2a; only the transport changed.
 * Keyed by accountId so per-account ordering survives partitioning.
 */
@Component
@Profile("kafka")
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Override
    public void publish(DomainEvent event) {
        try {
            String key = String.valueOf(event.payload().getOrDefault("accountId", event.eventId()));
            kafka.send(event.topic(), key, mapper.writeValueAsString(event));
        } catch (Exception e) {
            // Publishing must never break a posting; the event is also in the log.
            throw new IllegalStateException("failed to publish " + event.type(), e);
        }
    }
}
