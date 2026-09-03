package com.mend.config;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @PostConstruct
    public void validateRedisProperties() {
        if (redisHost == null || redisHost.isBlank()) {
            throw new IllegalStateException("Invalid Redis configuration: REDIS_HOST must not be empty.");
        }
        if (redisPort <= 0 || redisPort > 65535) {
            throw new IllegalStateException("Invalid Redis configuration: REDIS_PORT must be between 1 and 65535, but was " + redisPort);
        }
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    public String getRedisHost() {
        return redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }
}

