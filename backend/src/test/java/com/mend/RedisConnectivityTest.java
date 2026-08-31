package com.mend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RedisConnectivityTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void redisConnectivityTest_PingsRedisServerSuccessfully() {
        String pingResponse = redisTemplate.getConnectionFactory().getConnection().ping();
        assertThat(pingResponse).isEqualTo("PONG");
    }
}
