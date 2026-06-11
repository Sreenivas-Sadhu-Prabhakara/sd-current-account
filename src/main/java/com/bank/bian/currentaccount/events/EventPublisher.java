package com.bank.bian.currentaccount.events;

/**
 * Outbound event port. Phase 2a binds a logging adapter; the Kafka adapter
 * activates when the platform's Kafka lands (flagship flows: payments, fraud,
 * KYC). Domain code never knows the difference.
 */
public interface EventPublisher {

    void publish(DomainEvent event);
}
