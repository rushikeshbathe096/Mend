package com.mend.service;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.events.RedisEventConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class RedisStreamRetryServiceTest {

    @Autowired
    private RedisStreamRetryService retryService;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        webhookEventRepository.deleteAll();
        try {
            redisTemplate.delete(RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {
        }
    }

    @Test
    void getMaxAttempts_ReturnsConfiguredValue() {
        assertEquals(3, retryService.getMaxAttempts());
    }

    @Test
    void handleFailure_FirstFailure_RoutesToRetryStreamWithAttemptTwoAndDiagnosticFields() {
        try {
            redisTemplate.delete(RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {}
        UUID eventId = UUID.randomUUID();
        Map<Object, Object> valueMap = Map.of(
                "eventId", eventId.toString(),
                "externalEventId", "evt_ext_1",
                "merchantId", UUID.randomUUID().toString(),
                "eventType", "payment.failed",
                "occurredAt", "2026-08-31T22:00:00Z",
                "attempt", "1"
        );

        boolean routedOrRetried = retryService.handleFailure(RecordId.of("100-0"), valueMap, "Transient DB timeout");
        assertTrue(routedOrRetried);

        List<MapRecord<String, Object, Object>> retryRecords = redisTemplate.opsForStream().range(RedisEventConstants.RETRY_STREAM, Range.unbounded());
        assertNotNull(retryRecords);
        assertEquals(1, retryRecords.size());

        Map<Object, Object> recordVal = retryRecords.get(0).getValue();
        assertEquals("2", recordVal.get("attempt"));
        assertEquals("2", recordVal.get("attemptCount"));
        assertEquals("Transient DB timeout", recordVal.get("failureReason"));
        assertNotNull(recordVal.get("failedAt"));
        assertNull(recordVal.get("raw_payload"));
    }

    @Test
    void handleFailure_SecondFailure_RoutesToRetryStreamWithAttemptThree() {
        try {
            redisTemplate.delete(RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {}
        UUID eventId = UUID.randomUUID();
        Map<Object, Object> valueMap = Map.of(
                "eventId", eventId.toString(),
                "externalEventId", "evt_ext_2",
                "eventType", "payment.failed",
                "attempt", "2"
        );

        boolean routedOrRetried = retryService.handleFailure(RecordId.of("101-0"), valueMap, "Transient timeout retry");
        assertTrue(routedOrRetried);

        List<MapRecord<String, Object, Object>> retryRecords = redisTemplate.opsForStream().range(RedisEventConstants.RETRY_STREAM, Range.unbounded());
        assertNotNull(retryRecords);
        assertEquals(1, retryRecords.size());

        Map<Object, Object> recordVal = retryRecords.get(0).getValue();
        assertEquals("3", recordVal.get("attempt"));
        assertEquals("3", recordVal.get("attemptCount"));
    }

    @Test
    void handleFailure_ThirdFailureExceedsMax_RoutesToDlqStreamAndUpdatePostgres() {
        try {
            redisTemplate.delete(RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {}
        String extId = "evt_retry_exceeded_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), extId, "payment.failed");
        event = webhookEventRepository.save(event);

        Map<Object, Object> valueMap = Map.of(
                "eventId", event.getId().toString(),
                "externalEventId", extId,
                "merchantId", UUID.randomUUID().toString(),
                "eventType", "payment.failed",
                "occurredAt", "2026-08-31T22:00:00Z",
                "attempt", "3"
        );

        boolean routedToDlq = retryService.handleFailure(RecordId.of("200-0"), valueMap, "Persistent failure");
        assertTrue(routedToDlq);

        List<MapRecord<String, Object, Object>> dlqRecords = redisTemplate.opsForStream().range(RedisEventConstants.DLQ_STREAM, Range.unbounded());
        assertNotNull(dlqRecords);
        assertEquals(1, dlqRecords.size());

        Map<Object, Object> dlqVal = dlqRecords.get(0).getValue();
        assertEquals("3", dlqVal.get("attempt"));
        assertEquals("3", dlqVal.get("attemptCount"));
        assertEquals("Persistent failure", dlqVal.get("failureReason"));
        assertNotNull(dlqVal.get("failedAt"));
        assertNull(dlqVal.get("raw_payload"));

        WebhookEvent updated = webhookEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(WebhookEventStatus.FAILED, updated.getProcessingStatus());
        assertNotNull(updated.getErrorMessage());
        assertTrue(updated.getErrorMessage().contains("DLQ"));
    }

    @Test
    void handleFailure_DuplicateDelivery_ToleratedWithoutCorruptingState() {
        try {
            redisTemplate.delete(RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {}
        UUID eventId = UUID.randomUUID();
        Map<Object, Object> valueMap = Map.of(
                "eventId", eventId.toString(),
                "externalEventId", "evt_dup_test",
                "eventType", "payment.failed",
                "attempt", "1"
        );

        // First delivery failure
        retryService.handleFailure(RecordId.of("300-0"), valueMap, "Error 1");
        // Duplicate delivery failure
        retryService.handleFailure(RecordId.of("300-0"), valueMap, "Error 1 duplicate");

        List<MapRecord<String, Object, Object>> retryRecords = redisTemplate.opsForStream().range(RedisEventConstants.RETRY_STREAM, Range.unbounded());
        assertNotNull(retryRecords);
        assertEquals(2, retryRecords.size());
    }
}
