package com.mend.handler;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.event.WebhookEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

// Legacy handler disabled in favor of DefaultEventProcessingService and ClassificationService
public class DefaultWebhookEventHandler implements WebhookEventHandler {

    private static final Logger logger = LoggerFactory.getLogger(DefaultWebhookEventHandler.class);

    private final WebhookEventRepository webhookEventRepository;
    private boolean simulateFailure = false;

    public DefaultWebhookEventHandler(WebhookEventRepository webhookEventRepository) {
        this.webhookEventRepository = webhookEventRepository;
    }

    public void setSimulateFailure(boolean simulateFailure) {
        this.simulateFailure = simulateFailure;
    }

    @Override
    public boolean handle(WebhookEventEnvelope envelope) {
        if (envelope == null) {
            logger.warn("Received null WebhookEventEnvelope");
            return false;
        }

        logger.info("Processing stream event [eventId={}, type={}, merchantId={}]",
                envelope.getEventId(), envelope.getEventType(), envelope.getMerchantId());

        if (simulateFailure) {
            logger.warn("Simulated failure triggered for event [eventId={}]", envelope.getEventId());
            throw new RuntimeException("Simulated processing failure");
        }

        // 1. Schema version validation
        if (envelope.getSchemaVersion() > 1) {
            logger.warn("Unknown schema version {} for eventId {}. Handling safely.",
                    envelope.getSchemaVersion(), envelope.getEventId());
        }

        // 2. Safe handling of unknown or generic event types
        String eventType = envelope.getEventType() != null ? envelope.getEventType() : "unknown";
        switch (eventType) {
            case "payment.failed":
            case "payment.captured":
            case "payment.authorized":
            case "order.paid":
                logger.info("Handled recognized event type: {}", eventType);
                break;
            default:
                logger.info("Handled unknown/generic event type: {} safely without crashing", eventType);
                break;
        }

        // 3. Database status transition & Idempotency check
        if (envelope.getWebhookDatabaseId() != null) {
            Optional<WebhookEvent> eventOpt = webhookEventRepository.findById(envelope.getWebhookDatabaseId());
            if (eventOpt.isPresent()) {
                WebhookEvent event = eventOpt.get();
                if (event.getProcessingStatus() == WebhookEventStatus.PROCESSED) {
                    logger.info("Webhook event [eventId={}] already PROCESSED in DB. Skipping.", envelope.getEventId());
                    return true;
                }
                event.setProcessingStatus(WebhookEventStatus.PROCESSED);
                event.setProcessedAt(Instant.now());
                webhookEventRepository.save(event);
            }
        } else if (envelope.getEventId() != null) {
            Optional<WebhookEvent> eventOpt = webhookEventRepository.findByExternalEventId(envelope.getEventId());
            if (eventOpt.isPresent()) {
                WebhookEvent event = eventOpt.get();
                if (event.getProcessingStatus() == WebhookEventStatus.PROCESSED) {
                    logger.info("Webhook event [eventId={}] already PROCESSED in DB. Skipping.", envelope.getEventId());
                    return true;
                }
                event.setProcessingStatus(WebhookEventStatus.PROCESSED);
                event.setProcessedAt(Instant.now());
                webhookEventRepository.save(event);
            }
        }

        return true;
    }
}
