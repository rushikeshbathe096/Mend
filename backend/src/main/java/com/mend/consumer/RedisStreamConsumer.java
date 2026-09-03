package com.mend.consumer;

import tools.jackson.databind.ObjectMapper;
import com.mend.config.RedisStreamProperties;
import com.mend.dto.event.WebhookEventEnvelope;
import com.mend.handler.WebhookEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Legacy consumer disabled in favor of RedisWebhookEventConsumer
public class RedisStreamConsumer {

    private static final Logger logger = LoggerFactory.getLogger(RedisStreamConsumer.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisStreamProperties streamProperties;
    private final WebhookEventHandler webhookEventHandler;
    private final ObjectMapper objectMapper;
    private final String consumerName;

    public RedisStreamConsumer(StringRedisTemplate redisTemplate,
                               RedisStreamProperties streamProperties,
                               WebhookEventHandler webhookEventHandler,
                               ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.streamProperties = streamProperties;
        this.webhookEventHandler = webhookEventHandler;
        this.objectMapper = objectMapper;
        this.consumerName = "consumer-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public void initConsumerGroup() {
        String streamName = streamProperties.getStreamName();
        String groupName = streamProperties.getConsumerGroup();

        try {
            redisTemplate.opsForStream().createGroup(streamName, ReadOffset.from("0"), groupName);
            logger.info("Created Redis Stream consumer group '{}' for stream '{}'", groupName, streamName);
        } catch (Exception e) {
            // Group already exists or stream created on demand
            logger.debug("Consumer group '{}' already exists or initialized: {}", groupName, e.getMessage());
        }
    }

    public int pollAndProcessMessages() {
        initConsumerGroup();

        String streamName = streamProperties.getStreamName();
        String groupName = streamProperties.getConsumerGroup();

        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(groupName, consumerName),
                    StreamReadOptions.empty().count(10),
                    StreamOffset.create(streamName, ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) {
                return 0;
            }

            int processedCount = 0;
            for (MapRecord<String, Object, Object> record : records) {
                if (processSingleRecord(record, streamName, groupName)) {
                    processedCount++;
                }
            }
            return processedCount;

        } catch (Exception e) {
            logger.error("Error polling Redis stream '{}': {}", streamName, e.getMessage());
            return 0;
        }
    }

    public int processPendingMessages() {
        initConsumerGroup();

        String streamName = streamProperties.getStreamName();
        String groupName = streamProperties.getConsumerGroup();

        try {
            // Read unacknowledged pending messages starting from offset "0"
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(groupName, consumerName),
                    StreamReadOptions.empty().count(10),
                    StreamOffset.create(streamName, ReadOffset.from("0"))
            );

            if (records == null || records.isEmpty()) {
                return 0;
            }

            int processedCount = 0;
            for (MapRecord<String, Object, Object> record : records) {
                if (processSingleRecord(record, streamName, groupName)) {
                    processedCount++;
                }
            }
            return processedCount;

        } catch (Exception e) {
            logger.error("Error processing pending messages from stream '{}': {}", streamName, e.getMessage());
            return 0;
        }
    }

    private boolean processSingleRecord(MapRecord<String, Object, Object> record, String streamName, String groupName) {
        Map<Object, Object> valueMap = record.getValue();
        Object envelopeJsonObj = valueMap.get("envelope");

        if (envelopeJsonObj == null) {
            logger.warn("Stream record [id={}] missing envelope payload", record.getId());
            redisTemplate.opsForStream().acknowledge(streamName, groupName, record.getId());
            return false;
        }

        try {
            String envelopeJson = envelopeJsonObj.toString();
            WebhookEventEnvelope envelope = objectMapper.readValue(envelopeJson, WebhookEventEnvelope.class);

            logger.info("Stream consumer '{}' received event [eventId={}, recordId={}]",
                    consumerName, envelope.getEventId(), record.getId());

            boolean success = webhookEventHandler.handle(envelope);

            if (success) {
                redisTemplate.opsForStream().acknowledge(streamName, groupName, record.getId());
                logger.info("Stream record [id={}, eventId={}] successfully ACKed", record.getId(), envelope.getEventId());
                return true;
            } else {
                logger.warn("Handler returned false for event [eventId={}, recordId={}]. Record left un-ACKed.",
                        envelope.getEventId(), record.getId());
                return false;
            }

        } catch (Exception e) {
            logger.error("Failed to process stream record [id={}]: {}. Message left un-ACKed.",
                    record.getId(), e.getMessage());
            // DO NOT ACK on exception. Keeps message pending for retry/recovery.
            return false;
        }
    }

    public String getConsumerName() {
        return consumerName;
    }
}
