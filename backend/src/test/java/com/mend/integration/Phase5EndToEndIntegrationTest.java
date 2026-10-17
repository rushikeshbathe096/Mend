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
import com.mend.service.OutboxPublisherService;
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

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private HttpClient httpClient;
    private final java.util.List<String> ownEventIds = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void removeOwnRows() {
        com.mend.testsupport.TestRows.deleteWebhookEvents(jdbc, ownEventIds);
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        // Bug sweep item 6: own rows only (shared database); other classes' unpublished outbox rows are parked, not deleted.
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() + interval '30 days' WHERE status <> 'PUBLISHED'");
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
        ownEventIds.add(eventId);
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

        // 2. Ingestion commits before Redis publication.
        Optional<WebhookEvent> dbEventOpt = webhookEventRepository.findByExternalEventId(eventId);
        assertThat(dbEventOpt).isPresent();
        WebhookEvent dbEvent = dbEventOpt.get();
        assertThat(dbEvent.getPublishStatus()).isEqualTo(WebhookPublishStatus.PENDING);
        assertThat(dbEvent.getRawPayload()).isEqualTo(payload);

        // 3. Publish the committed outbox record and record success in PostgreSQL.
        assertThat(outboxPublisherService.publishDueEvents()).isEqualTo(1);
        dbEvent = webhookEventRepository.findByExternalEventId(eventId).orElseThrow();
        assertThat(dbEvent.getPublishStatus()).isEqualTo(WebhookPublishStatus.PUBLISHED);

        // 4. Poll Redis Stream and Process via Consumer
        int processedCount = redisWebhookEventConsumer.pollAndProcessMessages();
        assertThat(processedCount).isGreaterThanOrEqualTo(1);

        // 5. Verify DB Processing Status updated to PROCESSED
        WebhookEvent processedDbEvent = webhookEventRepository.findByExternalEventId(eventId).orElseThrow();
        assertThat(processedDbEvent.getProcessingStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        assertThat(processedDbEvent.getProcessedAt()).isNotNull();

        // 6. Verify Pending Messages in Redis Stream are ACKed
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

        // 7. Test Idempotent Duplicate Delivery
        HttpRequest duplicateRequest = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", validSignature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> duplicateResponse = httpClient.send(duplicateRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(duplicateResponse.statusCode()).isEqualTo(200);
        assertThat(duplicateResponse.body()).contains("DUPLICATE");

        // Verify only 1 webhook event entry exists in PostgreSQL DB for this event (was findAll() on a wiped table)
        assertThat(com.mend.testsupport.TestRows.countWebhookEvents(jdbc, eventId)).isEqualTo(1);
    }
}
