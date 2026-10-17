package com.mend.publisher;

import tools.jackson.databind.ObjectMapper;
import com.mend.config.RedisStreamProperties;
import com.mend.domain.entity.OutboxEvent;
import com.mend.dto.event.WebhookEventEnvelope;
import com.mend.dto.event.WebhookOutboxPayload;
import com.mend.events.RedisEventConstants;
import com.mend.events.WebhookEventMessage;
import com.mend.exception.WebhookPublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Primary
@ConditionalOnProperty(name = "mend.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisWebhookEventPublisher implements OutboxEventTransport {

    private static final Logger log = LoggerFactory.getLogger(RedisWebhookEventPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String streamName;

    public RedisWebhookEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, null, objectMapper);
    }

    @Autowired
    public RedisWebhookEventPublisher(
            StringRedisTemplate redisTemplate,
            @Autowired(required = false) RedisStreamProperties properties,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.streamName = (properties != null && properties.getStreamName() != null)
                ? properties.getStreamName()
                : RedisEventConstants.WEBHOOK_STREAM;
    }

    /**
     * Publishes an already-committed outbox record. This method deliberately has
     * no database dependency or state mutation; the caller records the outcome
     * in a separate, short transaction after Redis I/O finishes.
    */
    @Override
    public void publishOutbox(OutboxEvent outboxEvent) {
        if (outboxEvent == null) {
            throw new IllegalArgumentException("OutboxEvent cannot be null for publication");
        }
        try {
            WebhookOutboxPayload payload = objectMapper.readValue(
                    outboxEvent.getPayload(), WebhookOutboxPayload.class);
            if (payload.message() == null || payload.envelope() == null) {
                throw new IllegalArgumentException("Webhook outbox payload is incomplete");
            }
            publishRecord(
                    outboxEvent.getId(),
                    payload.message(),
                    payload.envelope(),
                    outboxEvent.getTraceId(),
                    outboxEvent.getCorrelationId());
        } catch (WebhookPublishException e) {
            throw e;
        } catch (Exception e) {
            throw new WebhookPublishException(
                    "Failed to publish outbox event to Redis stream: " + e.getMessage(), e);
        }
    }

    private RecordId publishRecord(java.util.UUID outboxId,
                                   WebhookEventMessage message,
                                   WebhookEventEnvelope envelope,
                                   String traceId,
                                   String correlationId) throws Exception {
        Map<String, String> fieldMap = new HashMap<>();
        if (outboxId != null) {
            fieldMap.put("outboxId", outboxId.toString());
            fieldMap.put("idempotencyKey", outboxId.toString());
        }
        fieldMap.put("eventId", message.eventId().toString());
        fieldMap.put("externalEventId", message.externalEventId() != null ? message.externalEventId() : "");
        fieldMap.put("merchantId", message.merchantId() != null ? message.merchantId().toString() : "");
        fieldMap.put("eventType", message.eventType() != null ? message.eventType() : "");
        fieldMap.put("occurredAt", message.occurredAt().toString());
        fieldMap.put("payloadHash", message.payloadHash() != null ? message.payloadHash() : "");
        fieldMap.put("version", message.version());
        fieldMap.put("correlationId", firstNonBlank(correlationId, traceId, message.eventId().toString()));
        fieldMap.put("traceId", firstNonBlank(traceId, correlationId, message.eventId().toString()));
        fieldMap.put("json", objectMapper.writeValueAsString(message));
        fieldMap.put("envelope", objectMapper.writeValueAsString(envelope));

        StringRecord record = StringRecord.of(fieldMap).withStreamKey(streamName);
        RecordId recordId = redisTemplate.opsForStream().add(record);
        if (recordId == null) {
            throw new IllegalStateException("Redis did not return a stream record ID");
        }
        log.info("Published outbox event {} for webhook {} to Redis stream '{}' with record ID {}",
                outboxId, message.eventId(), streamName, recordId);
        return recordId;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("At least one trace identifier must be available");
    }
}
