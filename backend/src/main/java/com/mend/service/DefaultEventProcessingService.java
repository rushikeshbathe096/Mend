package com.mend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mend.taxonomy.ProviderFailure;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.events.WebhookEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class DefaultEventProcessingService implements EventProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEventProcessingService.class);

    private final WebhookEventRepository webhookEventRepository;
    private final ClassificationService classificationService;
    private final PaymentReconciliationService paymentReconciliationService;
    private final ObjectMapper objectMapper;

    public DefaultEventProcessingService(
            WebhookEventRepository webhookEventRepository,
            @Autowired(required = false) ClassificationService classificationService,
            @Autowired(required = false) PaymentReconciliationService paymentReconciliationService,
            ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.classificationService = classificationService;
        this.paymentReconciliationService = paymentReconciliationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean processEvent(WebhookEventMessage eventMessage) {
        if (eventMessage == null) {
            log.warn("Received null WebhookEventMessage in downstream processing boundary");
            return false;
        }

        log.info("Processing webhook event in downstream boundary: [eventId={}, externalEventId={}, merchantId={}, type={}]",
                eventMessage.eventId(), eventMessage.externalEventId(), eventMessage.merchantId(), eventMessage.eventType());

        WebhookEvent event = null;

        if (eventMessage.eventId() != null) {
            event = webhookEventRepository.findById(eventMessage.eventId()).orElse(null);
        }

        if (event == null && eventMessage.externalEventId() != null) {
            event = webhookEventRepository.findByExternalEventId(eventMessage.externalEventId()).orElse(null);
        }

        if (event == null) {
            log.warn("Referenced webhook event [eventId={}, externalEventId={}] not found in PostgreSQL database",
                    eventMessage.eventId(), eventMessage.externalEventId());
            return false;
        }

        // Reconcile payment execution attempts if applicable
        if (paymentReconciliationService != null) {
            ReconciliationResult reconResult = paymentReconciliationService.reconcileWebhookEvent(event);
            if (reconResult.isReconciled()) {
                log.info("Webhook event [eventId={}] successfully reconciled with ActionIntent '{}', Campaign '{}': {}",
                        event.getId(), reconResult.getActionIntentId(), reconResult.getCampaignId(), reconResult.getMessage());
                return true;
            } else if (reconResult.getStatus() == ReconciliationResult.Status.SKIPPED_CAMPAIGN_LEVEL) {
                // M2 U8: the original payment / subscription of an existing campaign, or an ambiguous reference sent to
                // review. Handled: classifying it would create a campaign from an event that is not a new failure.
                log.info("Webhook event [eventId={}] handled at campaign level: {}", event.getId(), reconResult.getMessage());
                return true;
            } else if (reconResult.getStatus() == ReconciliationResult.Status.SKIPPED_ALREADY_FINALIZED) {
                log.info("Webhook event [eventId={}] skipped reconciliation (already finalized): {}",
                        event.getId(), reconResult.getMessage());
                return true;
            } else if (reconResult.getStatus() == ReconciliationResult.Status.SKIPPED_TENANT_MISMATCH) {
                log.warn("Webhook event [eventId={}] rejected due to tenant mismatch: {}",
                        event.getId(), reconResult.getMessage());
                return false;
            }
        }

        if (classificationService != null) {
            Optional<com.mend.domain.entity.ClassificationResult> existingResult =
                    classificationService.getClassificationByEventId(event.getId());

            if (existingResult.isPresent()) {
                log.info("Webhook event [eventId={}] already classified in DB. Idempotently skipping processing.", event.getId());
                return true;
            }

            ProviderFailure failure = extractProviderFailure(event);
            log.info("Classifying webhook event [eventId={}] (error_code='{}', error_reason='{}', error_source='{}')",
                    event.getId(), failure.code(), failure.reason(), failure.source());
            classificationService.classifyAndPersist(event, failure);
        } else {
            if (event.getProcessingStatus() == WebhookEventStatus.PROCESSED) {
                log.info("Webhook event [eventId={}] already PROCESSED in DB. Skipping duplicate processing.", event.getId());
                return true;
            }
            event.setProcessingStatus(WebhookEventStatus.PROCESSED);
            event.setProcessedAt(Instant.now());
            webhookEventRepository.save(event);
        }

        log.info("Successfully processed and marked event [eventId={}] as PROCESSED in PostgreSQL DB", event.getId());
        return true;
    }

    /**
     * M3 U2.2: the documented error fields of the Razorpay payment entity ({@code payload.payment.entity}). Untrusted input
     * (invariant 8): only read, never trusted beyond the taxonomy lookup. Anything missing stays null (unknown).
     */
    private ProviderFailure extractProviderFailure(WebhookEvent event) {
        return providerFailureOf(objectMapper, event);
    }

    /** M4 U4.8: the documented failure fields of a stored webhook (shared with the legacy reclassification). */
    static ProviderFailure providerFailureOf(ObjectMapper objectMapper, WebhookEvent event) {
        if (event.getRawPayload() != null && !event.getRawPayload().isBlank()) {
            try {
                JsonNode entity = objectMapper.readTree(event.getRawPayload()).path("payload").path("payment").path("entity");
                if (entity.isObject()) {
                    return ProviderFailure.of(text(entity, "error_code"), text(entity, "error_reason"),
                            text(entity, "error_source"));
                }
            } catch (Exception e) {
                log.debug("Could not parse rawPayload for failure details: {}", e.getMessage());
            }
        }
        return ProviderFailure.of(null, null, null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

