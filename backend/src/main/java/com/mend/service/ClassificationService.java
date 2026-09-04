package com.mend.service;

import com.mend.client.AiClassificationClient;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    private final AiClassificationClient aiClassificationClient;
    private final ClassificationResultRepository classificationResultRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final CampaignLifecycleService campaignLifecycleService;
    private final AuditService auditService;

    public ClassificationService(
            AiClassificationClient aiClassificationClient,
            ClassificationResultRepository classificationResultRepository,
            WebhookEventRepository webhookEventRepository,
            @Autowired(required = false) CampaignLifecycleService campaignLifecycleService,
            @Autowired(required = false) AuditService auditService) {
        this.aiClassificationClient = aiClassificationClient;
        this.classificationResultRepository = classificationResultRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.auditService = auditService;
    }

    @Transactional
    public ClassificationResult classifyAndPersist(WebhookEvent event, String failureCode, String failureReason) {
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

        ClassificationRequestDto request = ClassificationRequestDto.of(
                event.getId(),
                event.getEventType(),
                failureCode,
                failureReason,
                event.getSource(),
                event.getMerchantId()
        );

        ClassificationResponseDto response;
        try {
            response = aiClassificationClient.classify(request);
        } catch (Exception e) {
            log.warn("AI classification client call failed for eventId='{}': {}. Applying UNKNOWN fallback.", event.getId(), e.getMessage());
            response = new ClassificationResponseDto(
                    com.mend.domain.enums.FailureClass.UNKNOWN,
                    new java.math.BigDecimal("0.30"),
                    com.mend.domain.enums.RecommendedAction.REVIEW_REQUIRED,
                    "AI classification service unavailable: " + e.getMessage(),
                    "v1.0.0-fallback"
            );
        }

        ClassificationResult result = new ClassificationResult(
                null,
                event.getId(),
                null,
                response.classification().name(),
                response.confidence(),
                response.recommendedAction().name(),
                response.reason(),
                response.modelVersion()
        );

        java.util.Map<String, Object> safeEvidence = response.evidence() != null 
                ? new java.util.HashMap<>(response.evidence()) 
                : new java.util.HashMap<>();
        safeEvidence.putIfAbsent("eventId", event.getId());
        safeEvidence.putIfAbsent("merchantId", event.getMerchantId());
        safeEvidence.putIfAbsent("modelVersion", response.modelVersion());
        result.setEvidence(safeEvidence);

        ClassificationResult savedResult = classificationResultRepository.save(result);

        // Update event status to PROCESSED
        event.setProcessingStatus(WebhookEventStatus.PROCESSED);
        event.setProcessedAt(Instant.now());
        webhookEventRepository.save(event);

        log.info("Successfully classified and persisted eventId='{}' [classification='{}', confidence={}]",
                event.getId(), response.classification(), response.confidence());

        // Emit audit log
        if (auditService != null) {
            java.util.Map<String, Object> evidence = new java.util.HashMap<>();
            evidence.put("failureClass", response.classification().name());
            evidence.put("confidence", response.confidence());
            evidence.put("recommendedAction", response.recommendedAction().name());
            evidence.put("modelVersion", response.modelVersion());
            evidence.put("eventId", event.getId());

            auditService.logStructuredEvent(
                    event.getMerchantId(),
                    null,
                    "AI_CLASSIFICATION_COMPLETED",
                    "SYSTEM",
                    null,
                    "AI classification: " + response.classification() + " (Confidence: " + response.confidence() + ")",
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

    public Optional<ClassificationResult> getClassificationByEventId(java.util.UUID eventId) {
        return classificationResultRepository.findByEventId(eventId);
    }
}
