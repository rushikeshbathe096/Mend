package com.mend.service;

import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.taxonomy.FailureClassification;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.ProviderFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * M3 U2.2 (ADR-003): classifies a failed payment with the deterministic {@link FailureTaxonomy} from the provider's
 * documented error fields. No AI call, no confidence, no model version: the table version is recorded instead.
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    private final ClassificationResultRepository classificationResultRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final CampaignLifecycleService campaignLifecycleService;
    private final AuditService auditService;

    public ClassificationService(
            ClassificationResultRepository classificationResultRepository,
            WebhookEventRepository webhookEventRepository,
            @Autowired(required = false) CampaignLifecycleService campaignLifecycleService,
            @Autowired(required = false) AuditService auditService) {
        this.classificationResultRepository = classificationResultRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.auditService = auditService;
    }

    @Transactional
    public ClassificationResult classifyAndPersist(WebhookEvent event, ProviderFailure failure) {
        // Task 10: Check Idempotency via Database Constraint / Query
        Optional<ClassificationResult> existing = classificationResultRepository.findByEventId(event.getId());
        if (existing.isPresent()) {
            log.info("Idempotency check: Classification result already exists for eventId='{}'", event.getId());
            ClassificationResult existingResult = existing.get();
            if (campaignLifecycleService != null) {
                campaignLifecycleService.processClassificationResult(event, existingResult);
            }
            return existingResult;
        }

        FailureClassification classification = FailureTaxonomy.classify(failure);

        ClassificationResult result = new ClassificationResult(
                null,
                event.getId(),
                null,
                classification.failureClass().name(),
                null,
                null,
                reasoning(classification),
                null
        );
        result.setTaxonomyVersion(classification.tableVersion());

        java.util.Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("eventId", event.getId());
        evidence.put("merchantId", event.getMerchantId());
        evidence.putAll(classification.evidence(failure));
        result.setEvidence(evidence);

        ClassificationResult savedResult = classificationResultRepository.save(result);

        // Update event status to PROCESSED
        event.setProcessingStatus(WebhookEventStatus.PROCESSED);
        event.setProcessedAt(Instant.now());
        webhookEventRepository.save(event);

        log.info("Classified eventId='{}' as {} (matched on {}, table {})",
                event.getId(), classification.failureClass(), classification.matchedOn(), classification.tableVersion());

        if (auditService != null) {
            auditService.logStructuredEvent(
                    event.getMerchantId(),
                    null,
                    "FAILURE_CLASSIFIED",
                    "SYSTEM",
                    null,
                    "Deterministic classification: " + classification.failureClass() + " (" + classification.matchedOn() + ")",
                    null,
                    evidence
            );
        }

        // Process campaign lifecycle if campaign service is available
        if (campaignLifecycleService != null) {
            try {
                campaignLifecycleService.processClassificationResult(event, savedResult);
            } catch (Exception e) {
                log.error("Failed to process campaign lifecycle for eventId='{}': {}", event.getId(), e.getMessage(), e);
                // Re-throw so transaction rolls back cleanly or exception handled by processor
                throw e;
            }
        }

        return savedResult;
    }

    private static String reasoning(FailureClassification c) {
        return switch (c.matchedOn()) {
            case REASON -> "Razorpay error_reason '" + c.matchedValue() + "' (" + c.documentation() + ")";
            case SOURCE -> "No error_reason; Razorpay error_source '" + c.matchedValue() + "' (" + c.documentation() + ")";
            case NONE -> "No documented Razorpay error_reason or error_source mapping: UNCLASSIFIED";
        };
    }

    public Optional<ClassificationResult> getClassificationByEventId(java.util.UUID eventId) {
        return classificationResultRepository.findByEventId(eventId);
    }
}
