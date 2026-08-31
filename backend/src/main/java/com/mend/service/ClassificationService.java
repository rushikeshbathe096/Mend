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

    public ClassificationService(
            AiClassificationClient aiClassificationClient,
            ClassificationResultRepository classificationResultRepository,
            WebhookEventRepository webhookEventRepository) {
        this.aiClassificationClient = aiClassificationClient;
        this.classificationResultRepository = classificationResultRepository;
        this.webhookEventRepository = webhookEventRepository;
    }

    @Transactional
    public ClassificationResult classifyAndPersist(WebhookEvent event, String failureCode, String failureReason) {
        // Task 10: Check Idempotency via Database Constraint / Query
        Optional<ClassificationResult> existing = classificationResultRepository.findByEventId(event.getId());
        if (existing.isPresent()) {
            log.info("Idempotency check: Classification result already exists for eventId='{}'", event.getId());
            return existing.get();
        }

        ClassificationRequestDto request = ClassificationRequestDto.of(
                event.getId(),
                event.getEventType(),
                failureCode,
                failureReason,
                event.getSource(),
                event.getMerchantId()
        );

        ClassificationResponseDto response = aiClassificationClient.classify(request);

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

        ClassificationResult savedResult = classificationResultRepository.save(result);

        // Update event status to PROCESSED
        event.setProcessingStatus(WebhookEventStatus.PROCESSED);
        event.setProcessedAt(Instant.now());
        webhookEventRepository.save(event);

        log.info("Successfully classified and persisted eventId='{}' [classification='{}', confidence={}]",
                event.getId(), response.classification(), response.confidence());

        return savedResult;
    }

    public Optional<ClassificationResult> getClassificationByEventId(java.util.UUID eventId) {
        return classificationResultRepository.findByEventId(eventId);
    }
}
