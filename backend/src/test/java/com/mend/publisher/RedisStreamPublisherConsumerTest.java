package com.mend.publisher;

import tools.jackson.databind.ObjectMapper;
import com.mend.config.RedisStreamProperties;
import com.mend.consumer.RedisWebhookEventConsumer;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.event.WebhookEventEnvelope;
import com.mend.handler.DefaultWebhookEventHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class RedisStreamPublisherConsumerTest {

    @Autowired
    private RedisWebhookEventPublisher publisher;

    @Autowired
    private RedisWebhookEventConsumer consumer;

    @Autowired
    private com.mend.processor.DefaultWebhookEventProcessor processor;

    private DefaultWebhookEventHandler handler;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisStreamProperties streamProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private Merchant testMerchant;

    @BeforeEach
    void setUp() {
        handler = new DefaultWebhookEventHandler(webhookEventRepository);
        handler.setSimulateFailure(false);
        processor.setSimulateFailure(false);
        webhookEventRepository.deleteAll();
        try {
            redisTemplate.delete(streamProperties.getStreamName());
        } catch (Exception ignored) {
        }

        Merchant merchant = new Merchant(UUID.randomUUID(), "Stream Merchant");
        merchant.setExternalReference("acc_stream_" + UUID.randomUUID().toString().substring(0, 6));
        testMerchant = merchantRepository.save(merchant);
    }

    @Test
    void publish_ValidEventWithKnownMerchant_PublishesToRedisAndUpdateStatus() {
        String eventId = "evt_pub_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "payment.failed");
        event.setSource("RAZORPAY");
        event.setMerchantId(testMerchant.getId());
        event.setPayloadHash("hash123456789");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);

        publisher.publish(event);

        // Verify PostgreSQL publication status updated
        WebhookEvent updated = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookPublishStatus.PUBLISHED, updated.getPublishStatus());
        assertNotNull(updated.getPublishedAt());

        // Consumer reads and processes message
        int count = consumer.pollAndProcessMessages();
        assertEquals(1, count);

        // Verify handler updated processingStatus to PROCESSED
        WebhookEvent processed = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, processed.getProcessingStatus());
        assertNotNull(processed.getProcessedAt());
    }

    @Test
    void publish_UnknownMerchant_PreservesNullMerchantIdInEnvelope() throws Exception {
        String eventId = "evt_null_merch_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "payment.captured");
        event.setSource("RAZORPAY");
        event.setMerchantId(null); // Unknown merchant
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);

        publisher.publish(event);

        WebhookEvent updated = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookPublishStatus.PUBLISHED, updated.getPublishStatus());
        assertNull(updated.getMerchantId());

        // Process message
        int count = consumer.pollAndProcessMessages();
        assertEquals(1, count);
    }

    @Test
    void consumer_UnknownEventType_HandledSafelyWithoutCrashing() {
        String eventId = "evt_unknown_type_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "custom.unknown.event");
        event.setSource("RAZORPAY");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);

        publisher.publish(event);

        int count = consumer.pollAndProcessMessages();
        assertEquals(1, count);
    }

    @Test
    void consumer_UnknownSchemaVersion_HandledSafely() {
        WebhookEventEnvelope envelope = new WebhookEventEnvelope(
                "evt_future_schema",
                99, // Unknown high schema version
                "RAZORPAY",
                "evt_future_schema",
                "payment.failed",
                null,
                UUID.randomUUID(),
                Instant.now(),
                Instant.now(),
                "hash99"
        );

        boolean result = handler.handle(envelope);
        assertTrue(result);
    }

    @Test
    void consumer_HandlerFailure_DoesNotAcknowledgeMessageAndSupportsRecovery() {
        String eventId = "evt_fail_test_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "payment.failed");
        event.setSource("RAZORPAY");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);

        publisher.publish(event);

        // Simulate processor failure
        processor.setSimulateFailure(true);

        int processed = consumer.pollAndProcessMessages();
        // Processor failed on main stream, message moved to retry stream
        assertEquals(0, processed);

        // Turn off simulated failure
        processor.setSimulateFailure(false);

        // Poll again to process recovered message from retry stream
        int recovered = consumer.pollAndProcessMessages();
        assertEquals(1, recovered);

        WebhookEvent processedEvent = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, processedEvent.getProcessingStatus());
    }
}
