package com.bank.bian.currentaccount.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope for everything this service domain announces to the rest of the
 * bank. Topic names follow the platform convention (see
 * bian-platform/platform-infra/kafka/topics.yaml); the contract this repo
 * publishes/consumes is in api/events.yaml.
 */
public record DomainEvent(
        String eventId,
        String topic,
        String type,
        Instant occurredAt,
        Map<String, Object> payload
) {
    public static DomainEvent of(String topic, String type, Map<String, Object> payload) {
        return new DomainEvent("EVT-" + UUID.randomUUID(), topic, type, Instant.now(), payload);
    }
}
