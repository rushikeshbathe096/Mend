package com.mend.publisher;

import tools.jackson.databind.ObjectMapper;
import com.mend.config.RedisStreamProperties;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.event.WebhookEventEnvelope;
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
public class RedisWebhookEventPublisher implements WebhookEventPublisher {

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

        Map<String, String> fieldMap = new HashMap<>();
        fieldMap.put("eventId", message.eventId().toString());
        fieldMap.put("externalEventId", message.externalEventId() != null ? message.externalEventId() : "");
        fieldMap.put("merchantId", message.merchantId() != null ? message.merchantId().toString() : "");
        fieldMap.put("eventType", message.eventType() != null ? message.eventType() : "");
        fieldMap.put("occurredAt", message.occurredAt().toString());
        fieldMap.put("payloadHash", message.payloadHash() != null ? message.payloadHash() : "");
        fieldMap.put("version", message.version());

        try {
            String jsonPayload = objectMapper.writeValueAsString(message);
            String envelopeJson = objectMapper.writeValueAsString(envelope);
            fieldMap.put("json", jsonPayload);
            fieldMap.put("envelope", envelopeJson);
        } catch (Exception e) {
            log.warn("Could not serialize event payload to JSON: {}", e.getMessage());
        }

        try {
            StringRecord record = StringRecord.of(fieldMap).withStreamKey(streamName);
            RecordId recordId = redisTemplate.opsForStream().add(record);

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
}
