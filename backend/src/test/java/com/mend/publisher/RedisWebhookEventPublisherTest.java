package com.mend.publisher;

import tools.jackson.databind.ObjectMapper;
import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.entity.WebhookEvent;
import com.mend.dto.event.WebhookEventEnvelope;
import com.mend.dto.event.WebhookOutboxPayload;
import com.mend.events.RedisEventConstants;
import com.mend.events.WebhookEventMessage;
import com.mend.exception.WebhookPublishException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedisWebhookEventPublisherTest {

    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, String, String> streamOperations;
    private ObjectMapper objectMapper;
    private RedisWebhookEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) streamOperations);
        objectMapper = new ObjectMapper();
        publisher = new RedisWebhookEventPublisher(redisTemplate, objectMapper);
    }

    // The legacy publish(WebhookEvent) path was removed in cleanup Group 5; these three cases now cover publishOutbox,
    // with the payload built exactly as ingestion builds it (WebhookOutboxPayload.forWebhook).
    @Test
    void publishOutbox_PublishesEventWithExpectedFieldsAndNoRawPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        String externalEventId = "evt_rzp_998877";
        String eventType = "payment.failed";
        Instant createdAt = Instant.now();
        String payloadHash = "hash123456";
        String rawPayload = "{\"entity\":\"payment\",\"status\":\"failed\"}";

        WebhookEvent event = new WebhookEvent();
        event.setId(eventId);
        event.setExternalEventId(externalEventId);
        event.setMerchantId(merchantId);
        event.setEventType(eventType);
        event.setEventCreatedAt(createdAt);
        event.setPayloadHash(payloadHash);
        event.setRawPayload(rawPayload);

        when(streamOperations.add(any(StringRecord.class))).thenReturn(RecordId.of("1788123456789-0"));

        publisher.publishOutbox(outboxFor(event));

        ArgumentCaptor<StringRecord> captor = ArgumentCaptor.forClass(StringRecord.class);
        verify(streamOperations).add(captor.capture());

        StringRecord record = captor.getValue();
        assertEquals(RedisEventConstants.WEBHOOK_STREAM, record.getStream());

        Map<String, String> valueMap = record.getValue();
        assertEquals(eventId.toString(), valueMap.get("eventId"));
        assertEquals(externalEventId, valueMap.get("externalEventId"));
        assertEquals(merchantId.toString(), valueMap.get("merchantId"));
        assertEquals(eventType, valueMap.get("eventType"));
        assertEquals(createdAt.toString(), valueMap.get("occurredAt"));
        assertEquals(payloadHash, valueMap.get("payloadHash"));

        // Verify raw_payload is NOT present in Redis record
        assertFalse(valueMap.containsKey("raw_payload"), "raw_payload must NOT be published to Redis");
        assertFalse(valueMap.containsValue(rawPayload), "raw_payload content must NOT be in Redis record");
        assertTrue(valueMap.containsKey("json"), "JSON field must be included in message map");
    }

    @Test
    void publishOutbox_SurfacesRedisFailureAsWebhookPublishException() throws Exception {
        WebhookEvent event = new WebhookEvent();
        event.setId(UUID.randomUUID());
        event.setExternalEventId("evt_fail_1");
        event.setEventType("payment.failed");

        when(streamOperations.add(any(StringRecord.class)))
                .thenThrow(new RedisConnectionFailureException("Simulated Redis Connection Failure"));

        OutboxEvent outbox = outboxFor(event);
        WebhookPublishException exception = assertThrows(WebhookPublishException.class, () -> publisher.publishOutbox(outbox));
        assertTrue(exception.getMessage().contains("Failed to publish outbox event to Redis stream"));
    }

    @Test
    void publishOutbox_NullEvent_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> publisher.publishOutbox(null));
    }

    private OutboxEvent outboxFor(WebhookEvent event) throws Exception {
        return OutboxEvent.pendingWebhook(event.getId(), event.getMerchantId(),
                objectMapper.writeValueAsString(WebhookOutboxPayload.forWebhook(event)), "trace-1", "corr-1", Instant.now());
    }

    @Test
    void publishOutbox_PreservesIdentityAndTraceMetadata() throws Exception {
        UUID webhookId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        WebhookEventMessage message = new WebhookEventMessage(
                webhookId, "evt_trace_1", merchantId, "payment.failed",
                occurredAt, "payload-hash", WebhookEventMessage.DEFAULT_VERSION, "{\"safe\":true}");
        WebhookEventEnvelope envelope = new WebhookEventEnvelope(
                "evt_trace_1", 1, "RAZORPAY", "evt_trace_1", "payment.failed",
                merchantId, webhookId, occurredAt, occurredAt, "payload-hash");
        OutboxEvent outbox = OutboxEvent.pendingWebhook(
                webhookId, merchantId,
                objectMapper.writeValueAsString(new WebhookOutboxPayload(message, envelope)),
                "trace-123", "correlation-456", Instant.now());

        when(streamOperations.add(any(StringRecord.class))).thenReturn(RecordId.of("1788123456790-0"));

        publisher.publishOutbox(outbox);

        ArgumentCaptor<StringRecord> captor = ArgumentCaptor.forClass(StringRecord.class);
        verify(streamOperations).add(captor.capture());
        Map<String, String> fields = captor.getValue().getValue();
        assertEquals(outbox.getId().toString(), fields.get("outboxId"));
        assertEquals(outbox.getId().toString(), fields.get("idempotencyKey"));
        assertEquals(webhookId.toString(), fields.get("eventId"));
        assertEquals(merchantId.toString(), fields.get("merchantId"));
        assertEquals("payload-hash", fields.get("payloadHash"));
        assertEquals("trace-123", fields.get("traceId"));
        assertEquals("correlation-456", fields.get("correlationId"));
    }
}
