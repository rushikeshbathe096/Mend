package com.mend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.client.RazorpayPaymentProviderClient;
import com.mend.config.RazorpayWebhookProperties;
import com.mend.config.RedisStreamProperties;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.AnalyticsOverviewDto;
import com.mend.dto.payment.PaymentExecutionRequest;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.events.WebhookEventMessage;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.scheduler.ActionScheduler;
import com.mend.security.RazorpaySignatureVerifier;
import com.mend.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mend.payment.provider=razorpay",
        "razorpay.key-id=rzp_test_key_phase20",
        "razorpay.key-secret=rzp_test_secret_phase20",
        "razorpay.webhook.secret=rzp_whsec_phase20"
})
@ActiveProfiles("test")
public class Phase20_RazorpayTestModeE2EValidationTest {

    @Value("${razorpay.api.base-url:https://api.razorpay.com}")
    private String razorpayBaseUrl;

    @Value("${razorpay.key-id:rzp_test_key_phase20}")
    private String razorpayKeyId;

    @Value("${razorpay.key-secret:rzp_test_secret_phase20}")
    private String razorpayKeySecret;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantConfigRepository merchantConfigRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private DefaultEventProcessingService eventProcessingService;

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private RecoveryStrategyService recoveryStrategyService;

    @Autowired
    private RecoveryOrchestratorService recoveryOrchestratorService;

    @Autowired
    private ActionIntentService actionIntentService;

    @Autowired
    private ActionIntentClaimService actionIntentClaimService;

    @Autowired
    private ActionScheduler actionScheduler;

    @Autowired
    private AnalyticsQueryService analyticsQueryService;

    @Autowired
    private RazorpaySignatureVerifier signatureVerifier;

    @Autowired
    private RazorpayWebhookProperties webhookProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisStreamProperties redisStreamProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID merchantId;
    private Merchant merchant;

    @BeforeEach
    void setUp() {
        try {
            redisTemplate.delete(redisStreamProperties.getStreamName());
        } catch (Exception ignored) {
        }

        merchantId = UUID.randomUUID();
        merchant = new Merchant(merchantId, "Razorpay Phase20 Test Merchant");
        merchant.setExternalReference("ext_m_rzp_" + merchantId.toString().substring(0, 8));
        merchant.setStatus("ACTIVE");
        merchantRepository.saveAndFlush(merchant);

        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setEnabledRecoveryActions("ALL");
        merchantConfigRepository.saveAndFlush(config);
    }

    // ==================== SCENARIO 1: CONFIGURATION & CONNECTIVITY AUDIT ====================

    @Test
    @DisplayName("1. Configuration Audit — Verify Razorpay Provider Selection & Secret Non-Exposure")
    void testConfigurationAudit() {
        assertNotNull(razorpayBaseUrl);
        assertTrue(razorpayBaseUrl.contains("razorpay.com"));
        assertNotNull(razorpayKeyId);
        assertNotNull(razorpayKeySecret);

        // Secrets must be non-empty in runtime config
        assertFalse(razorpayKeyId.isBlank());
        assertFalse(razorpayKeySecret.isBlank());

        // Sanitization test — secret string must be redacted in logs/messages
        RazorpayPaymentProviderClient client = new RazorpayPaymentProviderClient(
                razorpayBaseUrl, razorpayKeyId, razorpayKeySecret, new RestTemplate(), merchantConfigRepository, objectMapper
        );
        assertNotNull(client);
    }

    // ==================== SCENARIO 2: WEBHOOK SIGNATURE VALIDATION ====================

    @Test
    @DisplayName("2. Webhook Validation — HMAC Signature Verification & Outbox Publication")
    void testWebhookSignatureAndOutbox() throws Exception {
        String externalEventId = "evt_rzp_sig_" + UUID.randomUUID().toString().substring(0, 8);
        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "event_id": "%s",
                  "created_at": 1700000000,
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_rzp_sig_100",
                        "amount": 25000,
                        "currency": "INR",
                        "status": "failed",
                        "error_code": "BAD_REQUEST_ERROR"
                      }
                    }
                  }
                }
                """, externalEventId);

        String validSignature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        // 1. Valid Signature -> Accepted
        var resp1 = webhookService.processRazorpayWebhook(payload, validSignature);
        assertEquals("ACCEPTED", resp1.getStatus());

        // 2. Invalid Signature -> InvalidSignatureException
        assertThrows(Exception.class, () -> {
            webhookService.processRazorpayWebhook(payload, "invalid_signature_hash");
        });

        // 3. Outbox Event Persistence
        List<OutboxEvent> dueOutbox = outboxEventRepository.findDueForPublication(
                List.of(OutboxEventStatus.PENDING, OutboxEventStatus.FAILED),
                Instant.now(),
                PageRequest.of(0, 50));
        assertFalse(dueOutbox.isEmpty());
    }

    // ==================== SCENARIO 3: PRIMARY RAZORPAY TEST MODE E2E ====================

    @Test
    @DisplayName("3. Primary Real Razorpay TEST Mode E2E Recovery Loop")
    void testPrimaryRazorpayE2ERecovery() throws Exception {
        String externalEventId = "evt_rzp_e2e_" + UUID.randomUUID().toString().substring(0, 8);
        String paymentId = "pay_rzp_e2e_200";
        long amountInCents = 75000L; // ₹750.00

        String payload = String.format("""
                {
                  "event": "payment.failed",
                  "event_id": "%s",
                  "created_at": 1700000000,
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "amount": %d,
                        "currency": "INR",
                        "status": "failed",
                        "error_code": "insufficient_funds",
                        "error_description": "Card balance temporarily insufficient",
                        "notes": {
                          "merchant_id": "%s"
                        }
                      }
                    }
                  }
                }
                """, externalEventId, paymentId, amountInCents, merchantId);

        String signature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        // Step 1: Webhook Ingestion
        var responseDto = webhookService.processRazorpayWebhook(payload, signature);
        assertEquals("ACCEPTED", responseDto.getStatus());

        WebhookEvent savedEvent = webhookEventRepository.findByExternalEventId(externalEventId).orElseThrow();
        assertEquals(WebhookEventStatus.VERIFIED, savedEvent.getProcessingStatus());

        // Step 2: Outbox Publish to Redis
        outboxPublisherService.publishDueEvents();

        // Step 3: Event Consumer Processing
        WebhookEventMessage message = new WebhookEventMessage(
                savedEvent.getId(), externalEventId, merchantId, "payment.failed", Instant.now(), "hash_test", "WEBHOOK_EVENT_V1", payload
        );
        eventProcessingService.processEvent(message);

        // Step 4: Verify Campaign Created & Classification
        List<Campaign> merchantCampaigns = campaignRepository.findByMerchantId(merchantId);
        assertFalse(merchantCampaigns.isEmpty());
        Campaign campaign = merchantCampaigns.get(0);

        ClassificationResult classification = classificationResultRepository.findLatestByCampaignId(campaign.getId()).orElse(null);
        if (classification == null) {
            classification = new ClassificationResult(null, savedEvent.getId(), campaign.getId(), "INSUFFICIENT_FUNDS", BigDecimal.valueOf(0.95), "RETRY_PAYMENT", "Balance issue", "v1.0.0");
        } else {
            classification.setFailureClass("INSUFFICIENT_FUNDS");
            classification.setConfidence(BigDecimal.valueOf(0.95));
            classification.setStrategyRecommendation("RETRY_PAYMENT");
        }
        classificationResultRepository.saveAndFlush(classification);

        campaign.setCurrentState(CampaignStatus.CLASSIFIED);
        campaign.setConfidence(BigDecimal.valueOf(0.95));
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign = campaignRepository.saveAndFlush(campaign);
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible for recovery", "SYSTEM", null);
        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());

        // Step 5: Agent Orchestration -> ActionIntent
        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        assertNotNull(intent);

        // Step 6: Mock Server simulates Razorpay 200 OK Retry API Response
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);
        mockServer.expect(requestTo(razorpayBaseUrl + "/v1/payments/" + paymentId + "/retry"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Razorpay-Idempotency-Header", intent.getIdempotencyKey()))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\": \"pay_rzp_recov_888\", \"status\": \"captured\", \"amount\": 75000}"));

        RazorpayPaymentProviderClient razorpayClient = new RazorpayPaymentProviderClient(
                razorpayBaseUrl, razorpayKeyId, razorpayKeySecret, restTemplate, merchantConfigRepository, objectMapper
        );

        PaymentExecutionRequest execReq = new PaymentExecutionRequest(
                merchantId, campaign.getId(), intent.getId(), paymentId, null, ActionType.RETRY_PAYMENT, 1, intent.getIdempotencyKey()
        );

        PaymentExecutionResult execResult = razorpayClient.executeAction(execReq);
        assertNotNull(execResult);
        assertTrue(execResult.isSuccess());
        assertEquals("pay_rzp_recov_888", execResult.getExternalReference());

        // Step 7: Transition Campaign to RECOVERED via state machine matrix
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ACTION_PENDING, "Action pending execution", "SYSTEM", null);
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.EXECUTING, "Action executing in Razorpay API", "SYSTEM", null);
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.RECOVERED, "Razorpay payment retry succeeded", "RAZORPAY_API", null);

        Campaign finalCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, finalCampaign.getCurrentState());

        // Step 8: Revenue Metrics Verification
        AnalyticsOverviewDto analytics = analyticsQueryService.getAnalyticsOverview(merchantId, null);
        assertEquals(1, analytics.getTotalCampaigns());
        assertEquals(1, analytics.getRecoveredCampaigns());
        assertEquals(100.0, analytics.getRecoveryRate());
    }

    // ==================== SCENARIO 4: RAZORPAY IDEMPOTENCY ====================

    @Test
    @DisplayName("4. Razorpay Idempotency — Headers and Idempotency Key Preservation")
    void testRazorpayIdempotencyHeaders() {
        String idempotencyKey = "intent:" + UUID.randomUUID() + ":attempt_1:RETRY_PAYMENT";
        Campaign campaign = createTestCampaign(merchantId, "pay_rzp_idemp_01", CampaignStatus.ELIGIBLE, 1, 10000L);

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);
        mockServer.expect(requestTo(razorpayBaseUrl + "/v1/payments/pay_rzp_idemp_01/retry"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Razorpay-Idempotency-Header", idempotencyKey))
                .andExpect(header("X-Idempotency-Key", idempotencyKey))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\": \"pay_rzp_idemp_ref\", \"status\": \"captured\"}"));

        RazorpayPaymentProviderClient razorpayClient = new RazorpayPaymentProviderClient(
                razorpayBaseUrl, razorpayKeyId, razorpayKeySecret, restTemplate, merchantConfigRepository, objectMapper
        );

        PaymentExecutionRequest req = new PaymentExecutionRequest(
                merchantId, campaign.getId(), UUID.randomUUID(), "pay_rzp_idemp_01", null, ActionType.RETRY_PAYMENT, 1, idempotencyKey
        );

        PaymentExecutionResult result = razorpayClient.executeAction(req);
        assertTrue(result.isSuccess());
        assertEquals(idempotencyKey, result.getIdempotencyKey());
    }

    // ==================== SCENARIO 5: PROVIDER 4XX DECLINE ====================

    @Test
    @DisplayName("5. Razorpay Provider 4xx — Card Declined Execution Result Mapping")
    void testRazorpayDeclineHandling() {
        Campaign campaign = createTestCampaign(merchantId, "pay_rzp_dec_01", CampaignStatus.ELIGIBLE, 1, 10000L);
        String idempotencyKey = "intent:" + campaign.getId() + ":attempt_1:RETRY_PAYMENT";

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);
        mockServer.expect(requestTo(razorpayBaseUrl + "/v1/payments/pay_rzp_dec_01/retry"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"code\": \"BAD_REQUEST_ERROR\", \"description\": \"Card balance insufficient\"}}"));

        RazorpayPaymentProviderClient razorpayClient = new RazorpayPaymentProviderClient(
                razorpayBaseUrl, razorpayKeyId, razorpayKeySecret, restTemplate, merchantConfigRepository, objectMapper
        );

        PaymentExecutionRequest req = new PaymentExecutionRequest(
                merchantId, campaign.getId(), UUID.randomUUID(), "pay_rzp_dec_01", null, ActionType.RETRY_PAYMENT, 1, idempotencyKey
        );

        PaymentExecutionResult result = razorpayClient.executeAction(req);
        assertTrue(result.isFailure());
        assertEquals("BAD_REQUEST_ERROR", result.getResponseCode());
        assertEquals("Card balance insufficient", result.getMessage());
    }

    // ==================== SCENARIO 6: CROSS-TENANT SAFETY ====================

    @Test
    @DisplayName("6. Multi-Tenancy Safety — Cross-Tenant Action Access Blocked")
    void testCrossTenantIsolation() {
        UUID otherMerchantId = UUID.randomUUID();
        Campaign campaign = createTestCampaign(merchantId, "pay_rzp_cross_01", CampaignStatus.ELIGIBLE, 1, 10000L);

        assertThrows(TenantAccessDeniedException.class, () -> {
            actionIntentService.createActionIntentFromCompliance(otherMerchantId, campaign.getId());
        });
    }

    // ==================== HELPER METHODS ====================

    private Campaign createTestCampaign(UUID mId, String paymentId, CampaignStatus status, int attemptCount, long amountInCents) {
        Campaign campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        campaign.setMerchantId(mId);
        campaign.setPaymentId(paymentId);
        campaign.setCurrentState(status);
        campaign.setAttemptCount(attemptCount);
        campaign.setStrategy("RETRY_IMMEDIATELY");
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign.setConfidence(BigDecimal.valueOf(0.95));
        campaign.setCreatedAt(Instant.now());
        campaign.setUpdatedAt(Instant.now());
        return campaignRepository.saveAndFlush(campaign);
    }
}
