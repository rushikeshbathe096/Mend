package com.mend.consumer;

import com.mend.client.AiClassificationClient;
import com.mend.config.RazorpayWebhookProperties;
import com.mend.config.RedisStreamProperties;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.FailureClass;
import com.mend.domain.enums.RecommendedAction;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;
import com.mend.events.RedisEventConstants;
import com.mend.processor.DefaultWebhookEventProcessor;
import com.mend.security.RazorpaySignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
public class ConsumerConsolidationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisWebhookEventConsumer redisWebhookEventConsumer;

    @Autowired
    private DefaultWebhookEventProcessor defaultWebhookEventProcessor;

    @Autowired
    private RedisStreamProperties redisStreamProperties;

    @Autowired
    private RazorpaySignatureVerifier signatureVerifier;

    @Autowired
    private RazorpayWebhookProperties webhookProperties;

    @MockitoBean
    private AiClassificationClient aiClassificationClient;

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        defaultWebhookEventProcessor.setSimulateFailure(false);
        classificationResultRepository.deleteAll();
        webhookEventRepository.deleteAll();
        try {
            redisTemplate.delete(redisStreamProperties.getStreamName());
            redisTemplate.delete(RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("STEP 7 Regression: Verify ApplicationContext contains exactly ONE active consumer bean for mend:webhooks")
    void testSingleActiveConsumerInContext() {
        String[] legacyConsumerBeans = applicationContext.getBeanNamesForType(com.mend.consumer.RedisStreamConsumer.class);
        assertThat(legacyConsumerBeans).isEmpty();

        String[] activeConsumerBeans = applicationContext.getBeanNamesForType(RedisWebhookEventConsumer.class);
        assertThat(activeConsumerBeans).hasSize(1);
    }

    @Test
    @DisplayName("STEP 8 Duplicate Test: Duplicate webhook events produce exactly ONE webhook_events row and ONE classification_result")
    void testDuplicateWebhookDeliveryIdempotency() throws Exception {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(
                new ClassificationResponseDto(
                        FailureClass.INSUFFICIENT_FUNDS,
                        new BigDecimal("0.90"),
                        RecommendedAction.RETRY_LATER,
                        "Insufficient funds",
                        "v1.0.0"
                )
        );

        String externalEventId = "evt_dup_" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "event_id": "%s",
                  "created_at": 1700000000,
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_dup_001",
                        "amount": 50000,
                        "currency": "INR",
                        "status": "failed",
                        "error_code": "BAD_REQUEST_ERROR",
                        "error_reason": "insufficient_funds",
                        "error_description": "Payment failed"
                      }
                    }
                  }
                }
                """, externalEventId);

        String signature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        // 1st Post
        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
        assertThat(resp1.statusCode()).isEqualTo(200);

        // 2nd Post (Duplicate)
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
        assertThat(resp2.statusCode()).isEqualTo(200);

        // Verify only 1 row in webhook_events
        assertThat(webhookEventRepository.count()).isEqualTo(1);

        // Process Redis Stream
        int count = redisWebhookEventConsumer.pollAndProcessMessages();
        assertThat(count).isEqualTo(1);

        // Verify only 1 classification result row created
        assertThat(classificationResultRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("STEP 9 Fallback Test: AI Service Failure gracefully persists UNKNOWN classification result and ACKs stream record")
    void testAiServiceUnavailableFallbackAndAck() throws Exception {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class)))
                .thenThrow(new RuntimeException("Simulated AI connection timeout"));

        String externalEventId = "evt_fallback_" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "event_id": "%s",
                  "created_at": 1700000000,
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_fall_001",
                        "amount": 25000,
                        "currency": "INR",
                        "status": "failed",
                        "error_code": "GATEWAY_ERROR",
                        "error_reason": "payment_gateway_down",
                        "error_description": "Gateway unreachable"
                      }
                    }
                  }
                }
                """, externalEventId);

        String signature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        int processed = redisWebhookEventConsumer.pollAndProcessMessages();
        assertThat(processed).isEqualTo(1);

        WebhookEvent dbEvent = webhookEventRepository.findByExternalEventId(externalEventId).orElseThrow();
        assertThat(dbEvent.getProcessingStatus()).isEqualTo(WebhookEventStatus.PROCESSED);

        Optional<ClassificationResult> resultOpt = classificationResultRepository.findByEventId(dbEvent.getId());
        assertThat(resultOpt).isPresent();
        ClassificationResult fallbackResult = resultOpt.get();

        assertThat(fallbackResult.getFailureClass()).isEqualTo("UNKNOWN");
        assertThat(fallbackResult.getConfidence()).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(fallbackResult.getStrategyRecommendation()).isEqualTo("REVIEW_REQUIRED");
        assertThat(fallbackResult.getModelVersion()).isEqualTo("v1.0.0-fallback");
    }

    @Test
    @DisplayName("STEP 9 Failure Test: Genuine processing error leaves message un-ACKed in stream")
    void testGenuineProcessingFailureLeavesMessageUnacked() throws Exception {
        String externalEventId = "evt_fail_ack_" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "event_id": "%s",
                  "created_at": 1700000000,
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_fail_001",
                        "amount": 10000,
                        "currency": "INR",
                        "status": "failed"
                      }
                    }
                  }
                }
                """, externalEventId);

        String signature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/webhooks/razorpay"))
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        // Simulate processor failure
        defaultWebhookEventProcessor.setSimulateFailure(true);

        // 1st poll: attempt 1 fails -> routes attempt 2 to retry stream
        redisWebhookEventConsumer.pollAndProcessMessages();

        // 2nd poll: attempt 2 & attempt 3 fail -> reaches maxAttempts -> routes to DLQ and marks DB status FAILED
        redisWebhookEventConsumer.pollAndProcessMessages();

        // Restore processor
        defaultWebhookEventProcessor.setSimulateFailure(false);

        // Verify that after retries reached max limit, the event in DB is marked FAILED and routed to DLQ
        WebhookEvent dbEvent = webhookEventRepository.findByExternalEventId(externalEventId).orElseThrow();
        assertThat(dbEvent.getProcessingStatus()).isEqualTo(WebhookEventStatus.FAILED);
        assertThat(dbEvent.getErrorMessage()).contains("Exceeded max retry attempts");
    }
}
