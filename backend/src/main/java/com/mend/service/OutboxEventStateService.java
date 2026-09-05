package com.mend.service;

import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.OutboxEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.domain.repository.WebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OutboxEventStateService {

    private final OutboxEventRepository outboxEventRepository;
    private final WebhookEventRepository webhookEventRepository;

    public OutboxEventStateService(OutboxEventRepository outboxEventRepository,
                                   WebhookEventRepository webhookEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
        this.webhookEventRepository = webhookEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID outboxId, Instant publishedAt) {
        OutboxEvent outbox = outboxEventRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox event not found: " + outboxId));
        if (outbox.getStatus() == OutboxEventStatus.PUBLISHED) {
            return;
        }

        outbox.setStatus(OutboxEventStatus.PUBLISHED);
        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setPublishedAt(publishedAt);
        outbox.setLastError(null);
        outbox.setUpdatedAt(publishedAt);
        outboxEventRepository.save(outbox);

        updateWebhookPublished(outbox, publishedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID outboxId, String error, Instant failedAt, Instant nextAttemptAt) {
        OutboxEvent outbox = outboxEventRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox event not found: " + outboxId));
        if (outbox.getStatus() == OutboxEventStatus.PUBLISHED) {
            return;
        }

        outbox.setStatus(OutboxEventStatus.FAILED);
        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setLastError(truncate(error, 4000));
        outbox.setNextAttemptAt(nextAttemptAt);
        outbox.setUpdatedAt(failedAt);
        outboxEventRepository.save(outbox);

        if (OutboxEvent.WEBHOOK_AGGREGATE_TYPE.equals(outbox.getAggregateType())) {
            webhookEventRepository.findById(outbox.getAggregateId()).ifPresent(webhook -> {
                webhook.setPublishStatus(WebhookPublishStatus.PUBLISH_FAILED);
                webhook.setErrorMessage("Redis publish failed: " + truncate(error, 3900));
                webhookEventRepository.save(webhook);
            });
        }
    }

    private void updateWebhookPublished(OutboxEvent outbox, Instant publishedAt) {
        if (!OutboxEvent.WEBHOOK_AGGREGATE_TYPE.equals(outbox.getAggregateType())) {
            return;
        }
        WebhookEvent webhook = webhookEventRepository.findById(outbox.getAggregateId())
                .orElseThrow(() -> new IllegalStateException(
                        "Webhook aggregate not found for outbox event " + outbox.getId()));
        webhook.setPublishStatus(WebhookPublishStatus.PUBLISHED);
        webhook.setPublishedAt(publishedAt);
        webhookEventRepository.save(webhook);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "Unknown Redis publication failure";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
