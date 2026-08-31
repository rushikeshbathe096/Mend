package com.mend.consumer;

import tools.jackson.databind.ObjectMapper;
import com.mend.events.RedisEventConstants;
import com.mend.events.WebhookEventMessage;
import com.mend.processor.WebhookEventProcessor;
import com.mend.service.RedisStreamRetryService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class RedisWebhookEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedisWebhookEventConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final WebhookEventProcessor webhookEventProcessor;
    private final RedisStreamRetryService retryService;
    private final ObjectMapper objectMapper;
    private final String streamName;
    private final String consumerGroup;
    private final String consumerName;

    @Autowired
    public RedisWebhookEventConsumer(
            StringRedisTemplate redisTemplate,
            WebhookEventProcessor webhookEventProcessor,
            @Autowired(required = false) RedisStreamRetryService retryService,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.webhookEventProcessor = webhookEventProcessor;
        this.retryService = retryService;
        this.objectMapper = objectMapper;
        this.streamName = RedisEventConstants.WEBHOOK_STREAM;
        this.consumerGroup = RedisEventConstants.CONSUMER_GROUP;
        this.consumerName = RedisEventConstants.CONSUMER_NAME;
    }

    @PostConstruct
    public void initConsumerGroup() {
        createGroupIfMissing(streamName);
        createGroupIfMissing(RedisEventConstants.RETRY_STREAM);
    }

    private void createGroupIfMissing(String targetStream) {
        try {
            redisTemplate.opsForStream().createGroup(targetStream, ReadOffset.from("0"), consumerGroup);
            log.info("Successfully created Redis Stream consumer group '{}' for stream '{}'", consumerGroup, targetStream);
        } catch (Exception e) {
            log.info("Consumer group '{}' already exists or stream initialized for '{}'", consumerGroup, targetStream);
        }
    }

    public int pollAndProcessMessages() {
        initConsumerGroup();
        int count = pollStream(streamName);
        count += pollStream(RedisEventConstants.RETRY_STREAM);
        return count;
    }

    private int pollStream(String targetStream) {
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamReadOptions.empty().count(10),
                    StreamOffset.create(targetStream, ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) {
                return 0;
            }

            int processedCount = 0;
            for (MapRecord<String, Object, Object> record : records) {
                if (processSingleRecord(targetStream, record)) {
                    processedCount++;
                }
            }
            return processedCount;
        } catch (Exception e) {
            log.error("Error polling Redis stream '{}' for group '{}': {}", targetStream, consumerGroup, e.getMessage());
            return 0;
        }
    }

    public int processPendingMessages() {
        initConsumerGroup();
        int count = processPendingStream(streamName);
        count += processPendingStream(RedisEventConstants.RETRY_STREAM);
        return count;
    }

    private int processPendingStream(String targetStream) {
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamReadOptions.empty().count(10),
                    StreamOffset.create(targetStream, ReadOffset.from("0"))
            );

            if (records == null || records.isEmpty()) {
                return 0;
            }

            int processedCount = 0;
            for (MapRecord<String, Object, Object> record : records) {
                if (processSingleRecord(targetStream, record)) {
                    processedCount++;
                }
            }
            return processedCount;
        } catch (Exception e) {
            log.error("Error processing pending messages from stream '{}': {}", targetStream, e.getMessage());
            return 0;
        }
    }

    private boolean processSingleRecord(String targetStream, MapRecord<String, Object, Object> record) {
        Map<Object, Object> valueMap = record.getValue();
        WebhookEventMessage message = deserializeRecord(record.getId().getValue(), valueMap);

        if (message == null) {
            log.warn("Failed to deserialize record [id={}] from stream '{}'.", record.getId(), targetStream);
            if (retryService != null) {
                boolean routedToDlq = retryService.handleFailure(record.getId(), valueMap, "Deserialization failure");
                if (routedToDlq) {
                    redisTemplate.opsForStream().acknowledge(targetStream, consumerGroup, record.getId());
                }
            }
            return false;
        }

        try {
            log.info("Consumer '{}' processing event [eventId={}, recordId={}] from stream '{}'", consumerName, message.eventId(), record.getId(), targetStream);
            boolean success = webhookEventProcessor.process(message);

            if (success) {
                redisTemplate.opsForStream().acknowledge(targetStream, consumerGroup, record.getId());
                log.info("Successfully processed and ACKed stream record [id={}, eventId={}] in stream '{}'", record.getId(), message.eventId(), targetStream);
                return true;
            } else {
                log.warn("Processor returned false for event [eventId={}, recordId={}] in stream '{}'.", message.eventId(), record.getId(), targetStream);
                if (retryService != null) {
                    boolean routedOrRetried = retryService.handleFailure(record.getId(), valueMap, "Processor returned false");
                    if (routedOrRetried) {
                        redisTemplate.opsForStream().acknowledge(targetStream, consumerGroup, record.getId());
                    }
                }
                return false;
            }
        } catch (Exception e) {
            log.error("Error processing stream record [id={}] in stream '{}': {}.", record.getId(), targetStream, e.getMessage());
            if (retryService != null) {
                boolean routedOrRetried = retryService.handleFailure(record.getId(), valueMap, e.getMessage());
                if (routedOrRetried) {
                    redisTemplate.opsForStream().acknowledge(targetStream, consumerGroup, record.getId());
                }
            }
            return false;
        }
    }

    private WebhookEventMessage deserializeRecord(String recordId, Map<Object, Object> valueMap) {
        try {
            Object jsonObj = valueMap.get("json");
            if (jsonObj != null) {
                return objectMapper.readValue(jsonObj.toString(), WebhookEventMessage.class);
            }

            Object eventIdObj = valueMap.get("eventId");
            Object externalEventIdObj = valueMap.get("externalEventId");
            Object merchantIdObj = valueMap.get("merchantId");
            Object eventTypeObj = valueMap.get("eventType");
            Object occurredAtObj = valueMap.get("occurredAt");
            Object payloadHashObj = valueMap.get("payloadHash");

            if (eventIdObj != null) {
                UUID eventId = parseUuid(eventIdObj.toString());
                String externalEventId = externalEventIdObj != null ? externalEventIdObj.toString() : null;
                UUID merchantId = merchantIdObj != null && !merchantIdObj.toString().isBlank() ? parseUuid(merchantIdObj.toString()) : null;
                String eventType = eventTypeObj != null ? eventTypeObj.toString() : null;
                Instant occurredAt = occurredAtObj != null ? Instant.parse(occurredAtObj.toString()) : Instant.now();
                String payloadHash = payloadHashObj != null ? payloadHashObj.toString() : null;

                return new WebhookEventMessage(eventId, externalEventId, merchantId, eventType, occurredAt, payloadHash);
            }

            Object envelopeObj = valueMap.get("envelope");
            if (envelopeObj != null) {
                Map<?, ?> envMap = objectMapper.readValue(envelopeObj.toString(), Map.class);
                Object dbIdObj = envMap.get("webhookDatabaseId");
                Object extIdObj = envMap.get("eventId");
                Object merchIdObj = envMap.get("merchantId");
                Object typeObj = envMap.get("eventType");
                Object hashObj = envMap.get("payloadHash");

                UUID eventId = dbIdObj != null ? parseUuid(dbIdObj.toString()) : null;
                String externalEventId = extIdObj != null ? extIdObj.toString() : null;
                UUID merchantId = merchIdObj != null ? parseUuid(merchIdObj.toString()) : null;
                String eventType = typeObj != null ? typeObj.toString() : null;
                String payloadHash = hashObj != null ? hashObj.toString() : null;

                return new WebhookEventMessage(eventId, externalEventId, merchantId, eventType, Instant.now(), payloadHash);
            }
        } catch (Exception e) {
            log.warn("Deserialization error for record [id={}]: {}", recordId, e.getMessage());
        }

        return null;
    }

    private UUID parseUuid(String str) {
        try {
            return UUID.fromString(str);
        } catch (Exception e) {
            return null;
        }
    }

    public String getConsumerName() {
        return consumerName;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public String getStreamName() {
        return streamName;
    }
}
