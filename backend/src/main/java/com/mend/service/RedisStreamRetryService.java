package com.mend.service;

import tools.jackson.databind.ObjectMapper;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.events.RedisEventConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RedisStreamRetryService {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamRetryService.class);

    private final StringRedisTemplate redisTemplate;
    private final WebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    @Autowired
    public RedisStreamRetryService(
            StringRedisTemplate redisTemplate,
            @Autowired(required = false) WebhookEventRepository webhookEventRepository,
            ObjectMapper objectMapper,
            @Value("${mend.redis.retry.max-attempts:3}") int maxAttempts) {
        this.redisTemplate = redisTemplate;
        this.webhookEventRepository = webhookEventRepository;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
    }

    public boolean handleFailure(RecordId recordId, Map<Object, Object> valueMap, String errorMessage) {
        int currentAttempt = 1;
        Object attemptObj = valueMap.get("attempt");
        if (attemptObj == null) {
            attemptObj = valueMap.get("attemptCount");
        }
        if (attemptObj != null) {
            try {
                currentAttempt = Integer.parseInt(attemptObj.toString());
            } catch (NumberFormatException ignored) {
            }
        }

        if (currentAttempt < maxAttempts) {
            int nextAttempt = currentAttempt + 1;
            log.warn("Failure processing record [id={}]. Scheduling attempt {}/{} in retry stream '{}'",
                    recordId, nextAttempt, maxAttempts, RedisEventConstants.RETRY_STREAM);

            Map<String, String> retryMap = new HashMap<>();
            valueMap.forEach((k, v) -> {
                if (k != null && v != null) {
                    retryMap.put(k.toString(), v.toString());
                }
            });
            retryMap.put("attempt", String.valueOf(nextAttempt));
            retryMap.put("attemptCount", String.valueOf(nextAttempt));
            retryMap.put("failureReason", errorMessage != null ? errorMessage : "Unknown processing error");
            retryMap.put("failedAt", Instant.now().toString());

            try {
                StringRecord retryRecord = StringRecord.of(retryMap).withStreamKey(RedisEventConstants.RETRY_STREAM);
                RecordId addedId = redisTemplate.opsForStream().add(retryRecord);
                return addedId != null; // True means retry stream published -> safe to ACK original
            } catch (Exception e) {
                log.error("Failed to publish retry event for record [id={}]: {}", recordId, e.getMessage());
                return false; // DO NOT ACK original if retry publish fails
            }
        } else {
            log.error("Record [id={}] reached max retry attempts ({}). Routing to DLQ stream '{}'",
                    recordId, maxAttempts, RedisEventConstants.DLQ_STREAM);

            Map<String, String> dlqMap = new HashMap<>();
            valueMap.forEach((k, v) -> {
                if (k != null && v != null) {
                    dlqMap.put(k.toString(), v.toString());
                }
            });
            dlqMap.put("attempt", String.valueOf(currentAttempt));
            dlqMap.put("attemptCount", String.valueOf(currentAttempt));
            dlqMap.put("failureReason", errorMessage != null ? errorMessage : "Exceeded max retry attempts");
            dlqMap.put("dlqReason", errorMessage != null ? errorMessage : "Exceeded max retry attempts");
            dlqMap.put("failedAt", Instant.now().toString());
            dlqMap.put("dlqAt", Instant.now().toString());

            try {
                StringRecord dlqRecord = StringRecord.of(dlqMap).withStreamKey(RedisEventConstants.DLQ_STREAM);
                RecordId addedId = redisTemplate.opsForStream().add(dlqRecord);

                if (addedId != null) {
                    Object eventIdObj = valueMap.get("eventId");
                    if (eventIdObj != null && webhookEventRepository != null) {
                        try {
                            UUID eventId = UUID.fromString(eventIdObj.toString());
                            Optional<WebhookEvent> dbOpt = webhookEventRepository.findById(eventId);
                            if (dbOpt.isPresent()) {
                                WebhookEvent event = dbOpt.get();
                                event.setProcessingStatus(WebhookEventStatus.FAILED);
                                event.setErrorMessage("Exceeded max retry attempts (" + maxAttempts + "). Routed to DLQ.");
                                webhookEventRepository.save(event);
                            }
                        } catch (Exception ex) {
                            log.warn("Could not update PostgreSQL status for DLQ event [id={}]: {}", eventIdObj, ex.getMessage());
                        }
                    }
                    return true; // DLQ published -> safe to ACK original
                }
                return false;
            } catch (Exception e) {
                log.error("Failed to publish DLQ event for record [id={}]: {}", recordId, e.getMessage());
                return false; // DO NOT ACK original if DLQ publish fails
            }
        }
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
