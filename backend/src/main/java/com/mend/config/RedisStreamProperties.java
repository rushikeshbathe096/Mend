package com.mend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RedisStreamProperties {

    private final String streamName;
    private final String consumerGroup;
    private final boolean enabled;

    public RedisStreamProperties(
            @Value("${mend.redis.stream-name:mend:webhook-events}") String streamName,
            @Value("${mend.redis.consumer-group:mend-processors}") String consumerGroup,
            @Value("${mend.redis.enabled:true}") boolean enabled) {
        this.streamName = streamName;
        this.consumerGroup = consumerGroup;
        this.enabled = enabled;
    }

    public String getStreamName() {
        return streamName;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
