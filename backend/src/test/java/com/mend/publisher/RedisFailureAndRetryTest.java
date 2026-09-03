package com.mend.publisher;

import com.mend.config.RedisStreamProperties;
import com.mend.consumer.RedisWebhookEventConsumer;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.service.WebhookPublisherRetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class RedisFailureAndRetryTest {

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private RedisWebhookEventPublisher realPublisher;

    @Autowired
    private WebhookPublisherRetryService retryService;

    @Autowired
    private RedisWebhookEventConsumer consumer;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisStreamProperties streamProperties;

    @BeforeEach
    void setUp() {
        webhookEventRepository.deleteAll();
        try {
            redisTemplate.delete(streamProperties.getStreamName());
        } catch (Exception ignored) {
        }
    }

    @Test
    void redisFailure_PostgresRecordRemainsDurableAndMarkedFailed() {
        StringRedisTemplate badRedisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        org.mockito.Mockito.when(badRedisTemplate.opsForStream())
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Simulated Redis Connection Refused"));

        RedisWebhookEventPublisher failingPublisher = new RedisWebhookEventPublisher(
                badRedisTemplate, streamProperties, webhookEventRepository, new tools.jackson.databind.ObjectMapper()
        );

        String eventId = "evt_redis_down_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "payment.failed");
        event.setSource("RAZORPAY");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);

        // Attempt publication with bad Redis connection
        try {
            failingPublisher.publish(event);
        } catch (com.mend.exception.WebhookPublishException ignored) {
        }

        // Verify PostgreSQL record STILL EXISTS and was NOT DELETED
        WebhookEvent dbEvent = webhookEventRepository.findById(event.getId()).orElse(null);
        assertNotNull(dbEvent, "PostgreSQL record must remain durable during Redis failure!");
        assertEquals(WebhookPublishStatus.PUBLISH_FAILED, dbEvent.getPublishStatus());
        assertTrue(dbEvent.getErrorMessage().contains("Redis publish failed"));
    }

    @Test
    void retryFailedPublications_RePublishesFailedEventsWhenRedisBecomesAvailable() {
        // 1. Simulate an event that failed publishing earlier
        String eventId = "evt_retry_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent failedEvent = new WebhookEvent(UUID.randomUUID(), eventId, "payment.failed");
        failedEvent.setSource("RAZORPAY");
        failedEvent.setPublishStatus(WebhookPublishStatus.PUBLISH_FAILED);
        failedEvent.setErrorMessage("Simulated prior failure");
        failedEvent = webhookEventRepository.save(failedEvent);

        // Verify event in DB is PUBLISH_FAILED
        List<WebhookEvent> failedList = webhookEventRepository.findByPublishStatus(WebhookPublishStatus.PUBLISH_FAILED);
        assertEquals(1, failedList.size());

        // 2. Retry publication with working Redis connection
        int retriedCount = retryService.retryFailedPublications();
        assertEquals(1, retriedCount);

        // 3. Verify PostgreSQL status updated to PUBLISHED
        WebhookEvent updatedEvent = webhookEventRepository.findById(failedEvent.getId()).orElseThrow();
        assertEquals(WebhookPublishStatus.PUBLISHED, updatedEvent.getPublishStatus());
        assertNotNull(updatedEvent.getPublishedAt());

        // 4. Verify Consumer consumes the retried event from Redis Stream
        int consumedCount = consumer.pollAndProcessMessages();
        assertEquals(1, consumedCount);

        WebhookEvent finalEvent = webhookEventRepository.findById(failedEvent.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, finalEvent.getProcessingStatus());
    }
}
