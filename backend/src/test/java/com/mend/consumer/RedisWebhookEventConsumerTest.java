package com.mend.consumer;

import com.mend.client.AiClassificationClient;
import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.FailureClass;
import com.mend.domain.enums.RecommendedAction;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.event.WebhookOutboxPayload;
import com.mend.service.OutboxEventStateService;
import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;
import com.mend.events.RedisEventConstants;
import com.mend.processor.DefaultWebhookEventProcessor;
import com.mend.publisher.RedisWebhookEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class RedisWebhookEventConsumerTest {

    @Autowired
    private RedisWebhookEventPublisher publisher;

    @Autowired
    private RedisWebhookEventConsumer consumer;

    @Autowired
    private DefaultWebhookEventProcessor processor;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxEventStateService outboxStateService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiClassificationClient aiClassificationClient;

    @BeforeEach
    void setUp() {
        processor.setSimulateFailure(false);
        webhookEventRepository.deleteAll();

        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(
                new ClassificationResponseDto(
                        FailureClass.INSUFFICIENT_FUNDS,
                        new BigDecimal("0.90"),
                        RecommendedAction.RETRY_LATER,
                        "Mocked test response",
                        "v1.0.0-test"
                )
        );

        try {
            redisTemplate.delete(RedisEventConstants.WEBHOOK_STREAM);
            redisTemplate.delete(RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {
        }
    }

    /** OutboxPublisherService.publishOne for one row: committed outbox row, Redis transport, then markPublished. */
    private void publishThroughOutbox(WebhookEvent event) throws Exception {
        OutboxEvent outbox = outboxEventRepository.saveAndFlush(OutboxEvent.pendingWebhook(event.getId(), event.getMerchantId(),
                objectMapper.writeValueAsString(WebhookOutboxPayload.forWebhook(event)),
                "trace-" + event.getId(), "corr-" + event.getId(), Instant.now()));
        publisher.publishOutbox(outbox);
        outboxStateService.markPublished(outbox.getId(), Instant.now());
    }

    @Test
    void initConsumerGroup_IsIdempotent() {
        assertDoesNotThrow(() -> {
            consumer.initConsumerGroup();
            consumer.initConsumerGroup();
        });
        assertEquals(RedisEventConstants.WEBHOOK_STREAM, consumer.getStreamName());
        assertEquals(RedisEventConstants.CONSUMER_GROUP, consumer.getConsumerGroup());
        assertEquals(RedisEventConstants.CONSUMER_NAME, consumer.getConsumerName());
    }

    @Test
    void pollAndProcessMessages_ValidMessage_ReadsDeserializesProcessesAndACKs() throws Exception {
        String eventId = "evt_consumer_test_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "payment.failed");
        event.setSource("RAZORPAY");
        event.setPayloadHash("hash987654321");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);

        publishThroughOutbox(event);

        int count = consumer.pollAndProcessMessages();
        if (count == 0) {
            count = consumer.processPendingMessages();
        }

        WebhookEvent processed = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, processed.getProcessingStatus());
        assertNotNull(processed.getProcessedAt());
    }

    @Test
    void pollAndProcessMessages_ProcessorFailure_DoesNotAcknowledgeMessage() throws Exception {
        String eventId = "evt_processor_fail_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "payment.failed");
        event.setSource("RAZORPAY");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);

        publishThroughOutbox(event);

        processor.setSimulateFailure(true);

        int processedCount = consumer.pollAndProcessMessages();
        assertEquals(0, processedCount);

        processor.setSimulateFailure(false);

        int recoveredCount = consumer.pollAndProcessMessages();
        assertEquals(1, recoveredCount);

        WebhookEvent finalEvent = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, finalEvent.getProcessingStatus());
    }

    @Test
    void pollAndProcessMessages_InvalidRecord_DoesNotIncorrectlyAcknowledge() {
        consumer.initConsumerGroup();
        redisTemplate.opsForStream().add(RedisEventConstants.WEBHOOK_STREAM, java.util.Map.of("invalidKey", "invalidValue"));

        int count = consumer.pollAndProcessMessages();
        assertEquals(0, count);
    }
}
