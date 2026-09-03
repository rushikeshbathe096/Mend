package com.mend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RedisConfigTest {

    @Test
    @DisplayName("Valid Redis host and port configuration passes validation")
    void testValidRedisConfig() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "redisHost", "localhost");
        ReflectionTestUtils.setField(config, "redisPort", 6379);

        config.validateRedisProperties();

        assertThat(config.getRedisHost()).isEqualTo("localhost");
        assertThat(config.getRedisPort()).isEqualTo(6379);
    }

    @Test
    @DisplayName("Empty Redis host throws IllegalStateException")
    void testInvalidRedisHostThrows() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "redisHost", "   ");
        ReflectionTestUtils.setField(config, "redisPort", 6379);

        assertThatThrownBy(config::validateRedisProperties)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_HOST must not be empty");
    }

    @Test
    @DisplayName("Invalid Redis port throws IllegalStateException")
    void testInvalidRedisPortThrows() {
        RedisConfig config = new RedisConfig();
        ReflectionTestUtils.setField(config, "redisHost", "localhost");
        ReflectionTestUtils.setField(config, "redisPort", -1);

        assertThatThrownBy(config::validateRedisProperties)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_PORT must be between 1 and 65535");
    }
}
