package com.mend.service;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.publisher.WebhookEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebhookPublisherRetryService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookPublisherRetryService.class);

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookEventPublisher webhookEventPublisher;

    public WebhookPublisherRetryService(WebhookEventRepository webhookEventRepository,
                                        WebhookEventPublisher webhookEventPublisher) {
        this.webhookEventRepository = webhookEventRepository;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    public int retryFailedPublications() {
        List<WebhookEvent> failedEvents = webhookEventRepository.findByPublishStatus(WebhookPublishStatus.PUBLISH_FAILED);
        int retriedCount = 0;

        for (WebhookEvent event : failedEvents) {
            logger.info("Retrying publication for webhook event [eventId={}]", event.getExternalEventId());
            webhookEventPublisher.publish(event);
            if (event.getPublishStatus() == WebhookPublishStatus.PUBLISHED) {
                retriedCount++;
            }
        }

        return retriedCount;
    }
}
