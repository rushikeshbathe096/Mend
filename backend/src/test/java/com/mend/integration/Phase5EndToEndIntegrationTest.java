package com.mend.integration;

import tools.jackson.databind.ObjectMapper;
import com.mend.config.RazorpayWebhookProperties;
import com.mend.config.RedisStreamProperties;
import com.mend.consumer.RedisWebhookEventConsumer;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.events.RedisEventConstants;
import com.mend.security.RazorpaySignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class Phase5EndToEndIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisWebhookEventConsumer redisWebhookEventConsumer;

    @Autowired
    private RedisStreamProperties redisStreamProperties;

    @Autowired
    private RazorpaySignatureVerifier signatureVerifier;

    @Autowired
    private RazorpayWebhookProperties webhookProperties;

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        webhookEventRepository.deleteAll();
        try {
            redisTemplate.delete(redisStreamProperties.getStreamName());
            redisTemplate.delete(RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("Phase 5 E2E: Webhook POST -> DB -> Redis Stream -> Consumer -> Downstream Processing -> ACK")
    void testEndToEndWebhookRedisPipeline() throws Exception {
        String eventId = "evt_p5_e2e_" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "event_id": "%s",
                  "created_at": 1700000000,
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_p5_001",
                        "amount": 50000,
                        "currency": "INR",
                        "status": "failed",
                        "error_code": "BAD_REQUEST_ERROR",
                        "error_description": "Payment failed due to insufficient funds"
                      }
                    }
                  }
                }
                """, eventId);

        String validSignature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        // 1. Post Webhook to Controller Endpoint
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", validSignature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ACCEPTED");

        // 2. Verify PostgreSQL durable source of truth (publish status is PUBLISHED)
        Optional<WebhookEvent> dbEventOpt = webhookEventRepository.findByExternalEventId(eventId);
        assertThat(dbEventOpt).isPresent();
        WebhookEvent dbEvent = dbEventOpt.get();
        assertThat(dbEvent.getPublishStatus()).isEqualTo(WebhookPublishStatus.PUBLISHED);
        assertThat(dbEvent.getRawPayload()).isEqualTo(payload);

        // 3. Poll Redis Stream and Process via Consumer
        int processedCount = redisWebhookEventConsumer.pollAndProcessMessages();
        assertThat(processedCount).isGreaterThanOrEqualTo(1);

        // 4. Verify DB Processing Status updated to PROCESSED
        WebhookEvent processedDbEvent = webhookEventRepository.findByExternalEventId(eventId).orElseThrow();
        assertThat(processedDbEvent.getProcessingStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        assertThat(processedDbEvent.getProcessedAt()).isNotNull();

        // 5. Verify Pending Messages in Redis Stream are ACKed
        try {
            org.springframework.data.redis.connection.stream.PendingMessagesSummary pending = redisTemplate.opsForStream().pending(
                    redisStreamProperties.getStreamName(),
                    redisStreamProperties.getConsumerGroup()
            );
            if (pending != null) {
                assertThat(pending.getTotalPendingMessages()).isEqualTo(0);
            }
        } catch (Exception ignored) {
        }

        // 6. Test Idempotent Duplicate Delivery
        HttpRequest duplicateRequest = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", validSignature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> duplicateResponse = httpClient.send(duplicateRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(duplicateResponse.statusCode()).isEqualTo(200);
        assertThat(duplicateResponse.body()).contains("DUPLICATE");

        // Verify only 1 webhook event entry exists in PostgreSQL DB
        List<WebhookEvent> eventsInDb = webhookEventRepository.findAll();
        assertThat(eventsInDb).hasSize(1);
    }
}
