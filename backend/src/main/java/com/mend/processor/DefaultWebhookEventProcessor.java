package com.mend.processor;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.events.WebhookEventMessage;
import com.mend.service.EventProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DefaultWebhookEventProcessor implements WebhookEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebhookEventProcessor.class);

    private final WebhookEventRepository webhookEventRepository;
    private final EventProcessingService eventProcessingService;
    private boolean simulateFailure = false;

    @Autowired
    public DefaultWebhookEventProcessor(
            @Autowired(required = false) WebhookEventRepository webhookEventRepository,
            @Autowired(required = false) EventProcessingService eventProcessingService) {
        this.webhookEventRepository = webhookEventRepository;
        this.eventProcessingService = eventProcessingService;
    }

    public void setSimulateFailure(boolean simulateFailure) {
        this.simulateFailure = simulateFailure;
    }

    @Override
    public boolean process(WebhookEventMessage message) {
        if (message == null) {
            log.warn("Received null WebhookEventMessage for processing");
            return false;
        }

        if (simulateFailure) {
            log.warn("Simulated processing failure triggered for event [eventId={}]", message.eventId());
            throw new RuntimeException("Simulated processing failure");
        }

        if (message.eventId() == null && message.externalEventId() == null) {
            log.warn("Invalid event message missing event identifiers");
            return false;
        }

        log.info("Processing stream event [eventId={}, externalEventId={}, merchantId={}, eventType={}]",
                message.eventId(), message.externalEventId(), message.merchantId(), message.eventType());

        if (eventProcessingService != null) {
            return eventProcessingService.processEvent(message);
        }

        if (webhookEventRepository != null) {
            WebhookEvent dbEvent = null;

            if (message.eventId() != null) {
                dbEvent = webhookEventRepository.findById(message.eventId()).orElse(null);
            }

            if (dbEvent == null && message.externalEventId() != null) {
                dbEvent = webhookEventRepository.findByExternalEventId(message.externalEventId()).orElse(null);
            }

            if (dbEvent != null) {
                if (dbEvent.getProcessingStatus() == WebhookEventStatus.PROCESSED) {
                    log.info("Webhook event [eventId={}] already PROCESSED in DB. Idempotently skipping.", dbEvent.getId());
                    return true;
                }

                dbEvent.setProcessingStatus(WebhookEventStatus.PROCESSED);
                dbEvent.setProcessedAt(Instant.now());
                webhookEventRepository.save(dbEvent);
                log.info("Updated status to PROCESSED for event [eventId={}]", dbEvent.getId());
                return true;
            }

            log.warn("Webhook event not found in PostgreSQL for message [eventId={}, externalEventId={}]. Skipping safely.",
                    message.eventId(), message.externalEventId());
            return false;
        }

        return true;
    }
}

