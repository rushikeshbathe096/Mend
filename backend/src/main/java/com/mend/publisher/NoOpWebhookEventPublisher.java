package com.mend.publisher;

import com.mend.domain.entity.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NoOpWebhookEventPublisher implements WebhookEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpWebhookEventPublisher.class);

    @Override
    public void publish(WebhookEvent event) {
        log.info("Phase 4 Webhook Gateway: Event {} ({}) verified and ready for Phase 5 event pipeline",
                event.getExternalEventId(), event.getEventType());
    }
}
