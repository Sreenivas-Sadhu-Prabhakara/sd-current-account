package com.bank.bian.currentaccount.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Default adapter (any profile except 'kafka'): events go to the log, wire-identical shapes. */
@Component
@Profile("!kafka")
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger("bian.events");

    @Override
    public void publish(DomainEvent event) {
        log.info("topic={} type={} id={} payload={}",
                event.topic(), event.type(), event.eventId(), event.payload());
    }
}
