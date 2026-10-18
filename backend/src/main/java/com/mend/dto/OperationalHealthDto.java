package com.mend.dto;

import java.time.Instant;

/**
 * Operational health (M3, ADR-003; invariant 3): only measured values. {@code status} is UP when the Redis stream
 * metrics could be read and UNKNOWN otherwise; a value that is not measured yet is null (metrics registration is M5),
 * never a placeholder number.
 */
public class OperationalHealthDto {

    private final String status;
    private final Long redisStreamLag;
    private final Long dlqEventCount;
    /** Not measured yet (M5 metrics): always null. */
    private final Long webhookProcessingLatencyMs;
    private final Instant timestamp;

    public OperationalHealthDto(String status, Long redisStreamLag, Long dlqEventCount) {
        this.status = status;
        this.redisStreamLag = redisStreamLag;
        this.dlqEventCount = dlqEventCount;
        this.webhookProcessingLatencyMs = null;
        this.timestamp = Instant.now();
    }

    public String getStatus() { return status; }
    public Long getRedisStreamLag() { return redisStreamLag; }
    public Long getDlqEventCount() { return dlqEventCount; }
    public Long getWebhookProcessingLatencyMs() { return webhookProcessingLatencyMs; }
    public Instant getTimestamp() { return timestamp; }
}
