package com.mend.dto;

import java.time.Instant;

public class OperationalHealthDto {

    private final String status;
    private final long webhookProcessingLatencyMs;
    private final long redisStreamLag;
    private final long dlqEventCount;
    private final long actionExecutionFailures;
    private final long providerErrors;
    private final String aiServiceAvailability;
    private final double aiFallbackRate;
    private final Instant timestamp;

    public OperationalHealthDto(
            String status,
            long webhookProcessingLatencyMs,
            long redisStreamLag,
            long dlqEventCount,
            long actionExecutionFailures,
            long providerErrors,
            String aiServiceAvailability,
            double aiFallbackRate) {
        this.status = status;
        this.webhookProcessingLatencyMs = webhookProcessingLatencyMs;
        this.redisStreamLag = redisStreamLag;
        this.dlqEventCount = dlqEventCount;
        this.actionExecutionFailures = actionExecutionFailures;
        this.providerErrors = providerErrors;
        this.aiServiceAvailability = aiServiceAvailability;
        this.aiFallbackRate = aiFallbackRate;
        this.timestamp = Instant.now();
    }

    public String getStatus() { return status; }
    public long getWebhookProcessingLatencyMs() { return webhookProcessingLatencyMs; }
    public long getRedisStreamLag() { return redisStreamLag; }
    public long getDlqEventCount() { return dlqEventCount; }
    public long getActionExecutionFailures() { return actionExecutionFailures; }
    public long getProviderErrors() { return providerErrors; }
    public String getAiServiceAvailability() { return aiServiceAvailability; }
    public double getAiFallbackRate() { return aiFallbackRate; }
    public Instant getTimestamp() { return timestamp; }
}
