package com.mend.publisher;

import tools.jackson.databind.ObjectMapper;
import com.mend.config.RedisStreamProperties;
import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.WebhookEventRepository;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@Primary
@ConditionalOnProperty(name = "mend.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisWebhookEventPublisher implements WebhookEventPublisher, OutboxEventTransport {

    private static final Logger log = LoggerFactory.getLogger(RedisWebhookEventPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WebhookEventRepository webhookEventRepository;
    private final String streamName;

    public RedisWebhookEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, null, null, objectMapper);
    }

    @Autowired
    public RedisWebhookEventPublisher(
            StringRedisTemplate redisTemplate,
            @Autowired(required = false) RedisStreamProperties properties,
            @Autowired(required = false) WebhookEventRepository webhookEventRepository,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.webhookEventRepository = webhookEventRepository;
        this.streamName = (properties != null && properties.getStreamName() != null)
                ? properties.getStreamName()
                : RedisEventConstants.WEBHOOK_STREAM;
    }

    @Override
    public void publish(WebhookEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("WebhookEvent cannot be null for publication");
        }

        Instant occurredAt = event.getEventCreatedAt() != null ? event.getEventCreatedAt() : event.getReceivedAt();
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }

        WebhookEventMessage message = new WebhookEventMessage(
                event.getId(),
                event.getExternalEventId(),
                event.getMerchantId(),
                event.getEventType(),
                occurredAt,
                event.getPayloadHash(),
                WebhookEventMessage.DEFAULT_VERSION,
                event.getRawPayload()
        );

        WebhookEventEnvelope envelope = new WebhookEventEnvelope(
                event.getExternalEventId(),
                1,
                event.getSource() != null ? event.getSource() : "RAZORPAY",
                event.getExternalEventId(),
                event.getEventType(),
                event.getMerchantId(),
                event.getId(),
                occurredAt,
                event.getReceivedAt() != null ? event.getReceivedAt() : occurredAt,
                event.getPayloadHash()
        );

        try {
            String correlationId = firstNonBlank(
                    org.slf4j.MDC.get("correlationId"),
                    org.slf4j.MDC.get("traceId"),
                    event.getId() != null ? event.getId().toString() : java.util.UUID.randomUUID().toString());
            String traceId = firstNonBlank(org.slf4j.MDC.get("traceId"), correlationId);
            RecordId recordId = publishRecord(null, message, envelope, traceId, correlationId);

            // Compatibility path for callers that explicitly publish a WebhookEvent.
            // Webhook ingestion no longer uses this method; it writes an outbox row.
            event.setPublishStatus(WebhookPublishStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            if (webhookEventRepository != null) {
                webhookEventRepository.save(event);
            }

            log.info("Published webhook event {} (external ID: {}) to Redis stream '{}' with record ID {}",
                    event.getId(), event.getExternalEventId(), streamName, recordId);
        } catch (Exception e) {
            log.error("Failed to publish webhook event {} to Redis stream '{}': {}",
                    event.getId(), streamName, e.getMessage(), e);

            event.setPublishStatus(WebhookPublishStatus.PUBLISH_FAILED);
            event.setErrorMessage("Redis publish failed: " + e.getMessage());
            if (webhookEventRepository != null) {
                webhookEventRepository.save(event);
            }

            throw new WebhookPublishException("Failed to publish webhook event to Redis stream: " + e.getMessage(), e);
        }
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
