package com.mend.service;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.publisher.WebhookEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebhookPublisherRetryService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookPublisherRetryService.class);

    private final WebhookEventRepository webhookEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final WebhookEventPublisher webhookEventPublisher;
    private final ObjectProvider<OutboxPublisherService> outboxPublisherService;

    public WebhookPublisherRetryService(WebhookEventRepository webhookEventRepository,
                                        OutboxEventRepository outboxEventRepository,
                                        WebhookEventPublisher webhookEventPublisher,
                                        ObjectProvider<OutboxPublisherService> outboxPublisherService) {
        this.webhookEventRepository = webhookEventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.webhookEventPublisher = webhookEventPublisher;
        this.outboxPublisherService = outboxPublisherService;
    }

    public int retryFailedPublications() {
        // Current ingestion failures are retried from the transactional outbox.
        // Keep the legacy scan only for pre-outbox/orphaned records so upgrades
        // do not strand events created by an older application version.
        OutboxPublisherService publisherService = outboxPublisherService.getIfAvailable();
        int retriedCount = publisherService != null ? publisherService.publishDueEvents() : 0;

        List<WebhookEvent> failedEvents = webhookEventRepository.findByPublishStatus(WebhookPublishStatus.PUBLISH_FAILED);

        for (WebhookEvent event : failedEvents) {
            if (outboxEventRepository.existsByAggregateTypeAndAggregateId(
                    OutboxEvent.WEBHOOK_AGGREGATE_TYPE, event.getId())) {
                continue;
            }
            logger.info("Retrying publication for webhook event [eventId={}]", event.getExternalEventId());
            webhookEventPublisher.publish(event);
            if (event.getPublishStatus() == WebhookPublishStatus.PUBLISHED) {
                retriedCount++;
            }
        }

        return retriedCount;
    }
}
