package com.mend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

            FailureDetails failureDetails = extractFailureDetails(event);
            log.info("Classifying webhook event [eventId={}] via ClassificationService (code='{}', reason='{}')",
                    event.getId(), failureDetails.failureCode(), failureDetails.failureReason());
            classificationService.classifyAndPersist(event, failureDetails.failureCode(), failureDetails.failureReason());
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

    private FailureDetails extractFailureDetails(WebhookEvent event) {
        String failureCode = event.getErrorMessage();
        String failureReason = event.getErrorMessage();

        if (event.getRawPayload() != null && !event.getRawPayload().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(event.getRawPayload());
                if (root.has("payload") && root.get("payload").has("payment") && root.get("payload").get("payment").has("entity")) {
                    JsonNode entity = root.get("payload").get("payment").get("entity");
                    if (entity.has("error_reason") && !entity.get("error_reason").isNull()) {
                        failureCode = entity.get("error_reason").asText();
                    } else if (entity.has("error_code") && !entity.get("error_code").isNull()) {
                        failureCode = entity.get("error_code").asText();
                    }
                    if (entity.has("error_description") && !entity.get("error_description").isNull()) {
                        failureReason = entity.get("error_description").asText();
                    }
                } else {
                    if (failureCode == null || failureCode.isBlank()) {
                        if (root.has("error_code")) failureCode = root.get("error_code").asText();
                        else if (root.has("failure_code")) failureCode = root.get("failure_code").asText();
                        else if (root.has("code")) failureCode = root.get("code").asText();
                    }
                    if (failureReason == null || failureReason.isBlank()) {
                        if (root.has("error_description")) failureReason = root.get("error_description").asText();
                        else if (root.has("failure_reason")) failureReason = root.get("failure_reason").asText();
                        else if (root.has("reason")) failureReason = root.get("reason").asText();
                    }
                }
            } catch (Exception e) {
                log.debug("Could not parse rawPayload for failure details: {}", e.getMessage());
            }
        }

        if (failureCode == null || failureCode.isBlank()) {
            failureCode = event.getEventType() != null ? event.getEventType() : "UNKNOWN";
        }
        if (failureReason == null || failureReason.isBlank()) {
            failureReason = "Payment failure event: " + failureCode;
        }

        return new FailureDetails(failureCode, failureReason);
    }

    private record FailureDetails(String failureCode, String failureReason) {}
}

