package com.mend.events;

public final class RedisEventConstants {

    public static final String WEBHOOK_STREAM = "mend:webhooks";
    public static final String RETRY_STREAM = "mend:webhooks:retry";
    public static final String DLQ_STREAM = "mend:webhooks:dlq";
    public static final String CONSUMER_GROUP = "mend-webhook-processors";
    public static final String CONSUMER_NAME = "backend-consumer";

    private RedisEventConstants() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
