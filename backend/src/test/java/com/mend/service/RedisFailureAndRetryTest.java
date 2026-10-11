package com.mend.service;

import com.mend.config.RedisStreamProperties;
import com.mend.consumer.RedisWebhookEventConsumer;
import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.OutboxEventStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.event.WebhookOutboxPayload;
import com.mend.publisher.RedisWebhookEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis failure and retry on the transactional outbox, against real PostgreSQL and Redis. These replaced the tests of the
 * legacy WebhookPublisherRetryService (never scheduled in production, removed in cleanup Group 5). Each test drives
 * {@link OutboxPublisherService#publishOne} on its own row only, so other rows in the shared run database are untouched.
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisFailureAndRetryTest {

    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxEventStateService stateService;
    @Autowired private RedisWebhookEventPublisher realPublisher;
    @Autowired private RedisWebhookEventConsumer consumer;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RedisStreamProperties streamProperties;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        try {
            redisTemplate.delete(streamProperties.getStreamName());
        } catch (Exception ignored) {
        }
    }

    private OutboxPublisherService publisherWith(RedisWebhookEventPublisher transport) {
        return new OutboxPublisherService(outboxEventRepository, transport, stateService, 10, 1, 60, false);
    }

    private RedisWebhookEventPublisher redisDown() {
        StringRedisTemplate badRedisTemplate = mock(StringRedisTemplate.class);
        when(badRedisTemplate.opsForStream())
                .thenThrow(new RedisConnectionFailureException("Simulated Redis Connection Refused"));
        return new RedisWebhookEventPublisher(badRedisTemplate, objectMapper);
    }

    /** A verified webhook and its committed outbox row, as ingestion leaves them. */
    private OutboxEvent ingested(String prefix) throws Exception {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), prefix + UUID.randomUUID().toString().substring(0, 8), "payment.failed");
        event.setSource("RAZORPAY");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);
        return outboxEventRepository.saveAndFlush(OutboxEvent.pendingWebhook(event.getId(), event.getMerchantId(),
                objectMapper.writeValueAsString(WebhookOutboxPayload.forWebhook(event)),
                "trace-" + event.getId(), "corr-" + event.getId(), Instant.now()));
    }

    @Test
    void redisFailure_PostgresRecordRemainsDurableAndMarkedFailed() throws Exception {
        OutboxEvent outbox = ingested("evt_redis_down_");

        assertFalse(publisherWith(redisDown()).publishOne(outbox), "publication must report the Redis failure");

        WebhookEvent dbEvent = webhookEventRepository.findById(outbox.getAggregateId()).orElse(null);
        assertNotNull(dbEvent, "PostgreSQL record must remain durable during Redis failure!");
        assertEquals(WebhookPublishStatus.PUBLISH_FAILED, dbEvent.getPublishStatus());
        assertTrue(dbEvent.getErrorMessage().contains("Redis publish failed"));

        OutboxEvent failed = outboxEventRepository.findById(outbox.getId()).orElseThrow();
        assertEquals(OutboxEventStatus.FAILED, failed.getStatus(), "the outbox row stays retryable");
        assertEquals(1, failed.getAttempts());
        assertNotNull(failed.getLastError());
        assertTrue(failed.getNextAttemptAt().isAfter(failed.getUpdatedAt()), "a retry is scheduled with backoff");
    }

    @Test
    void retryFailedPublications_RePublishesFailedEventsWhenRedisBecomesAvailable() throws Exception {
        OutboxEvent outbox = ingested("evt_retry_");
        assertFalse(publisherWith(redisDown()).publishOne(outbox));
        OutboxEvent failed = outboxEventRepository.findById(outbox.getId()).orElseThrow();
        assertEquals(OutboxEventStatus.FAILED, failed.getStatus());

        // Redis is back: the same row is published on retry.
        assertTrue(publisherWith(realPublisher).publishOne(failed));

        OutboxEvent published = outboxEventRepository.findById(outbox.getId()).orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, published.getStatus());
        assertEquals(2, published.getAttempts());
        WebhookEvent updatedEvent = webhookEventRepository.findById(outbox.getAggregateId()).orElseThrow();
        assertEquals(WebhookPublishStatus.PUBLISHED, updatedEvent.getPublishStatus());
        assertNotNull(updatedEvent.getPublishedAt());

        // The consumer receives the retried event from the Redis stream.
        int consumedCount = consumer.pollAndProcessMessages();
        assertEquals(1, consumedCount);
        WebhookEvent finalEvent = webhookEventRepository.findById(outbox.getAggregateId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, finalEvent.getProcessingStatus());
    }
}
