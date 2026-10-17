package com.mend.publisher;

import tools.jackson.databind.ObjectMapper;
import com.mend.config.RedisStreamProperties;
import com.mend.consumer.RedisWebhookEventConsumer;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.event.WebhookEventEnvelope;
import com.mend.dto.event.WebhookOutboxPayload;
import com.mend.service.OutboxEventStateService;
import com.mend.testsupport.TestRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Publication through the transactional outbox (the only production path; the legacy publish(WebhookEvent) chain was
 * removed in cleanup Group 5) into the real Redis stream, consumed by the one active consumer.
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisStreamPublisherConsumerTest {

    @Autowired
    private RedisWebhookEventPublisher publisher;

    @Autowired
    private RedisWebhookEventConsumer consumer;

    @Autowired
    private com.mend.processor.DefaultWebhookEventProcessor processor;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxEventStateService outboxStateService;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisStreamProperties streamProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private Merchant testMerchant;

    @Autowired
    private JdbcTemplate jdbc;

    // Bug sweep item 6: this class deletes only the webhook events it saved (shared database), not the whole table.
    private final java.util.List<java.util.UUID> ownEventIds = new java.util.ArrayList<>();

    @AfterEach
    void removeOwnRows() {
        TestRows.deleteWebhookEventsById(jdbc, ownEventIds);
        TestRows.deleteMerchant(jdbc, testMerchant.getId());
    }

    @BeforeEach
    void setUp() {
        processor.setSimulateFailure(false);
        try {
            redisTemplate.delete(streamProperties.getStreamName());
            redisTemplate.delete(com.mend.events.RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(com.mend.events.RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {
        }

        Merchant merchant = new Merchant(UUID.randomUUID(), "Stream Merchant");
        merchant.setExternalReference("acc_stream_" + UUID.randomUUID().toString().substring(0, 6));
        testMerchant = merchantRepository.save(merchant);
    }

    /** OutboxPublisherService.publishOne for one row: committed outbox row, Redis transport, then markPublished. */
    private void publishThroughOutbox(WebhookOutboxPayload payload, WebhookEvent event) throws Exception {
        OutboxEvent outbox = outboxEventRepository.saveAndFlush(OutboxEvent.pendingWebhook(event.getId(), event.getMerchantId(),
                objectMapper.writeValueAsString(payload), "trace-" + event.getId(), "corr-" + event.getId(), Instant.now()));
        publisher.publishOutbox(outbox);
        outboxStateService.markPublished(outbox.getId(), Instant.now());
    }

    private void publishThroughOutbox(WebhookEvent event) throws Exception {
        publishThroughOutbox(WebhookOutboxPayload.forWebhook(event), event);
    }

    @Test
    void publish_ValidEventWithKnownMerchant_PublishesToRedisAndUpdateStatus() throws Exception {
        String eventId = "evt_pub_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "payment.failed");
        event.setSource("RAZORPAY");
        event.setMerchantId(testMerchant.getId());
        event.setPayloadHash("hash123456789");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);
        ownEventIds.add(event.getId());

        publishThroughOutbox(event);

        // Verify PostgreSQL publication status updated
        WebhookEvent updated = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookPublishStatus.PUBLISHED, updated.getPublishStatus());
        assertNotNull(updated.getPublishedAt());

        // Consumer reads and processes message
        int count = consumer.pollAndProcessMessages();
        if (count == 0) {
            count = consumer.processPendingMessages();
        }

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
        ownEventIds.add(event.getId());

        publishThroughOutbox(event);

        WebhookEvent updated = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookPublishStatus.PUBLISHED, updated.getPublishStatus());
        assertNull(updated.getMerchantId());

        // Process message
        int count = consumer.pollAndProcessMessages();
        if (count == 0) {
            count = consumer.processPendingMessages();
        }
        WebhookEvent processed = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, processed.getProcessingStatus());
    }

    @Test
    void consumer_UnknownEventType_HandledSafelyWithoutCrashing() throws Exception {
        String eventId = "evt_unknown_type_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "custom.unknown.event");
        event.setSource("RAZORPAY");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);
        ownEventIds.add(event.getId());

        publishThroughOutbox(event);

        int count = consumer.pollAndProcessMessages();
        if (count == 0) {
            count = consumer.processPendingMessages();
        }
        WebhookEvent processed = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, processed.getProcessingStatus());
    }

    @Test
    void consumer_UnknownSchemaVersion_HandledSafely() throws Exception {
        // Was asserted on the legacy DefaultWebhookEventHandler (removed in cleanup Group 5); now an envelope with an
        // unknown schema version goes through the outbox transport and the real consumer.
        String eventId = "evt_future_schema_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "payment.failed");
        event.setSource("RAZORPAY");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);
        ownEventIds.add(event.getId());
        WebhookOutboxPayload current = WebhookOutboxPayload.forWebhook(event);
        WebhookEventEnvelope e = current.envelope();
        WebhookEventEnvelope futureSchema = new WebhookEventEnvelope(e.getEventId(), 99, e.getProvider(), e.getProviderEventId(),
                e.getEventType(), e.getMerchantId(), e.getWebhookDatabaseId(), e.getOccurredAt(), e.getReceivedAt(), e.getPayloadHash());

        publishThroughOutbox(new WebhookOutboxPayload(current.message(), futureSchema), event);

        int count = consumer.pollAndProcessMessages();
        if (count == 0) {
            count = consumer.processPendingMessages();
        }
        assertEquals(1, count);
        WebhookEvent processed = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.PROCESSED, processed.getProcessingStatus());
    }

    @Test
    void consumer_HandlerFailure_DoesNotAcknowledgeMessageAndSupportsRecovery() throws Exception {
        String eventId = "evt_fail_test_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), eventId, "payment.failed");
        event.setSource("RAZORPAY");
        event.setPublishStatus(WebhookPublishStatus.PENDING);
        event = webhookEventRepository.save(event);
        ownEventIds.add(event.getId());

        publishThroughOutbox(event);

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
