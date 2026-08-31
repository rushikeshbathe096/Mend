package com.mend.publisher;

import tools.jackson.databind.ObjectMapper;
import com.mend.domain.entity.WebhookEvent;
import com.mend.events.RedisEventConstants;
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

    @Test
    void publish_PublishesEventWithExpectedFieldsAndNoRawPayload() {
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

        publisher.publish(event);

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
    void publish_SurfacesRedisFailureAsWebhookPublishException() {
        WebhookEvent event = new WebhookEvent();
        event.setId(UUID.randomUUID());
        event.setExternalEventId("evt_fail_1");
        event.setEventType("payment.failed");

        when(streamOperations.add(any(StringRecord.class)))
                .thenThrow(new RedisConnectionFailureException("Simulated Redis Connection Failure"));

        WebhookPublishException exception = assertThrows(WebhookPublishException.class, () -> publisher.publish(event));
        assertTrue(exception.getMessage().contains("Failed to publish webhook event to Redis stream"));
    }

    @Test
    void publish_NullEvent_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(null));
    }
}
