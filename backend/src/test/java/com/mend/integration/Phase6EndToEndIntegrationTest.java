package com.mend.integration;

import com.mend.client.AiClassificationClient;
import com.mend.config.RazorpayWebhookProperties;
import com.mend.config.RedisStreamProperties;
import com.mend.consumer.RedisWebhookEventConsumer;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.FailureClass;
import com.mend.domain.enums.RecommendedAction;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;
import com.mend.events.RedisEventConstants;
import com.mend.security.RazorpaySignatureVerifier;
import com.mend.service.OutboxPublisherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class Phase6EndToEndIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

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

    @MockitoBean
    private AiClassificationClient aiClassificationClient;

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

        // Configure default mock AI response
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(
                new ClassificationResponseDto(
                        FailureClass.INSUFFICIENT_FUNDS,
                        new BigDecimal("0.95"),
                        RecommendedAction.RETRY_LATER,
                        "Low account balance detected; schedule retry",
                        "v1.0.0-rule-based"
                )
        );
    }

    @Test
    @DisplayName("Phase 6 E2E: Webhook POST -> DB -> Redis Stream -> Consumer -> AI Classification -> PostgreSQL classification_results")
    void testEndToEndWebhookAiClassificationPipeline() throws Exception {
        String externalEventId = "evt_p6_e2e_" + UUID.randomUUID().toString().substring(0, 8);
        ownEventIds.add(externalEventId);
        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "event_id": "%s",
                  "created_at": 1700000000,
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_p6_001",
                        "amount": 75000,
                        "currency": "INR",
                        "status": "failed",
                        "error_code": "BAD_REQUEST_ERROR",
                        "error_reason": "insufficient_funds",
                        "error_description": "Payment failed due to insufficient funds"
                      }
                    }
                  }
                }
                """, externalEventId);

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
        Optional<WebhookEvent> dbEventOpt = webhookEventRepository.findByExternalEventId(externalEventId);
        assertThat(dbEventOpt).isPresent();
        WebhookEvent dbEvent = dbEventOpt.get();
        assertThat(dbEvent.getPublishStatus()).isEqualTo(WebhookPublishStatus.PENDING);

        // 3. Publish the committed outbox record and record success in PostgreSQL.
        assertThat(outboxPublisherService.publishDueEvents()).isEqualTo(1);
        dbEvent = webhookEventRepository.findByExternalEventId(externalEventId).orElseThrow();
        assertThat(dbEvent.getPublishStatus()).isEqualTo(WebhookPublishStatus.PUBLISHED);

        // 4. Poll Redis Stream and Process via Consumer (which calls DefaultEventProcessingService -> ClassificationService)
        int processedCount = redisWebhookEventConsumer.pollAndProcessMessages();
        assertThat(processedCount).isGreaterThanOrEqualTo(1);

        // 5. Verify DB Processing Status updated to PROCESSED
        WebhookEvent processedDbEvent = webhookEventRepository.findByExternalEventId(externalEventId).orElseThrow();
        assertThat(processedDbEvent.getProcessingStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        assertThat(processedDbEvent.getProcessedAt()).isNotNull();

        // 6. Verify Phase 6 ClassificationResult is persisted in PostgreSQL
        Optional<ClassificationResult> classificationOpt = classificationResultRepository.findByEventId(dbEvent.getId());
        assertThat(classificationOpt).isPresent();
        ClassificationResult classificationResult = classificationOpt.get();

        assertThat(classificationResult.getEventId()).isEqualTo(dbEvent.getId());
        assertThat(classificationResult.getFailureClass()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(classificationResult.getConfidence()).isEqualByComparingTo(new BigDecimal("0.95"));
        assertThat(classificationResult.getStrategyRecommendation()).isEqualTo("RETRY_LATER");
        assertThat(classificationResult.getReasoning()).isEqualTo("Low account balance detected; schedule retry");
        assertThat(classificationResult.getModelVersion()).isEqualTo("v1.0.0-rule-based");

        // Verify AI Client was invoked with extracted failure code
        verify(aiClassificationClient, times(1)).classify(argThat(req ->
                "insufficient_funds".equals(req.failureCode()) &&
                "Payment failed due to insufficient funds".equals(req.failureReason())
        ));

        // 7. Test Duplicate Delivery Idempotency: Re-processing should not re-classify
        int secondPollCount = redisWebhookEventConsumer.pollAndProcessMessages();
        assertThat(secondPollCount).isEqualTo(0);

        // One classification for this event (was count() on a wiped table)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM classification_results c JOIN webhook_events w ON c.event_id = w.id "
                + "WHERE w.external_event_id = ?", Long.class, externalEventId)).isEqualTo(1);
    }
}
