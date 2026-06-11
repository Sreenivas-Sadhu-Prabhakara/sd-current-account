package com.bank.bian.currentaccount.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Phase 2a adapter: events go to the log, shaped exactly as Kafka will see them. */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger("bian.events");

    @Override
    public void publish(DomainEvent event) {
        log.info("topic={} type={} id={} payload={}",
                event.topic(), event.type(), event.eventId(), event.payload());
    }
}
