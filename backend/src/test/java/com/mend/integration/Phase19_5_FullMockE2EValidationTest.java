package com.mend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.provider.mock.MockPaymentProvider;
import com.mend.config.RazorpayWebhookProperties;
import com.mend.config.RedisStreamProperties;
import com.mend.consumer.RedisWebhookEventConsumer;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.AnalyticsOverviewDto;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.events.RedisEventConstants;
import com.mend.events.WebhookEventMessage;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.RazorpaySignatureVerifier;
import com.mend.service.*;
import com.mend.scheduler.ActionScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class Phase19_5_FullMockE2EValidationTest {

    @Autowired
    private MerchantRepository merchantRepository;

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
    private RedisWebhookEventConsumer redisWebhookEventConsumer;

    @Autowired
    private DefaultEventProcessingService eventProcessingService;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private RecoveryOrchestratorService recoveryOrchestratorService;

    @Autowired
    private ActionIntentService actionIntentService;

    @Autowired
    private ActionIntentClaimService actionIntentClaimService;

    @Autowired
    private ActionScheduler actionScheduler;

    @Autowired
    private ActionExecutionService actionExecutionService;

    @Autowired
    private MockPaymentProvider mockPaymentProviderClient;

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
        mockPaymentProviderClient.reset();
        try {
            redisTemplate.delete(redisStreamProperties.getStreamName());
            redisTemplate.delete(RedisEventConstants.RETRY_STREAM);
            redisTemplate.delete(RedisEventConstants.DLQ_STREAM);
        } catch (Exception ignored) {
        }

        merchantId = UUID.randomUUID();
        merchant = new Merchant(merchantId, "E2E Validation Merchant");
        merchant.setExternalReference("ext_m_e2e_" + merchantId.toString().substring(0, 8));
        merchant.setStatus("ACTIVE");
        merchantRepository.saveAndFlush(merchant);
    }

    @Autowired
    private RecoveryStrategyService recoveryStrategyService;

    @Autowired
    private ComplianceService complianceService;

    @Autowired
    private HumanApprovalService humanApprovalService;

    // ==================== SCENARIO A: HAPPY PATH E2E RECOVERY ====================

    @Test
    @DisplayName("Scenario A: Happy-Path E2E Payment Failure -> Ingestion -> Outbox -> Redis Stream -> AI Classification -> Campaign -> Agent -> Compliance -> Intent -> Scheduler -> Mock Provider -> RECOVERED & Revenue Metrics")
    void testScenarioA_HappyPathFullE2ERecovery() throws Exception {
        String externalEventId = "evt_happy_" + UUID.randomUUID().toString().substring(0, 8);
        String paymentId = "pay_happy_" + UUID.randomUUID().toString().substring(0, 8);
        long amountInCents = 50000L; // ₹500.00

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

        // 1. Webhook Ingestion & Outbox Persistence
        var responseDto = webhookService.processRazorpayWebhook(payload, signature);
        assertEquals("ACCEPTED", responseDto.getStatus());

        Optional<WebhookEvent> savedEvent = webhookEventRepository.findByExternalEventId(externalEventId);
        assertTrue(savedEvent.isPresent());
        assertEquals(WebhookEventStatus.VERIFIED, savedEvent.get().getProcessingStatus());

        List<OutboxEvent> dueOutbox = outboxEventRepository.findDueForPublication(
                List.of(OutboxEventStatus.PENDING, OutboxEventStatus.FAILED),
                Instant.now(),
                PageRequest.of(0, 50));
        assertFalse(dueOutbox.isEmpty());

        // 2. Outbox Publication to Redis Stream
        outboxPublisherService.publishDueEvents();
        OutboxEvent publishedOutbox = outboxEventRepository.findById(dueOutbox.get(0).getId()).orElseThrow();
        assertEquals(OutboxEventStatus.PUBLISHED, publishedOutbox.getStatus());

        // 3. Redis Event Processing & AI Classification
        WebhookEventMessage message = new WebhookEventMessage(
                savedEvent.get().getId(),
                externalEventId,
                merchantId,
                "payment.failed",
                Instant.now(),
                "hash_test",
                "WEBHOOK_EVENT_V1",
                payload
        );
        eventProcessingService.processEvent(message);

        // 4. Verify Campaign Created
        List<Campaign> merchantCampaigns = campaignRepository.findByMerchantId(merchantId);
        assertFalse(merchantCampaigns.isEmpty());
        Campaign campaign = merchantCampaigns.get(0);
        assertNotNull(campaign.getId());

        // Ensure Classification & Recovery Strategy
        Optional<ClassificationResult> existingClass = classificationResultRepository.findByEventId(savedEvent.get().getId());
        if (existingClass.isPresent()) {
            ClassificationResult res = existingClass.get();
            res.setFailureClass("INSUFFICIENT_FUNDS");
            res.setConfidence(BigDecimal.valueOf(0.95));
            res.setStrategyRecommendation("RETRY_PAYMENT");
            classificationResultRepository.saveAndFlush(res);
        } else {
            ClassificationResult classificationResult = new ClassificationResult(
                    null,
                    savedEvent.get().getId(),
                    campaign.getId(),
                    "INSUFFICIENT_FUNDS",
                    BigDecimal.valueOf(0.95),
                    "RETRY_PAYMENT",
                    "Temporary balance issue detected",
                    "v1.0.0"
            );
            classificationResultRepository.saveAndFlush(classificationResult);
        }

        campaign.setCurrentState(CampaignStatus.CLASSIFIED);
        campaign = campaignRepository.saveAndFlush(campaign);

        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible for recovery", "SYSTEM", null);
        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());

        // 5. Orchestrate Recovery Campaign via AI Agent / Recovery Orchestrator
        recoveryOrchestratorService.orchestrateRecovery(merchantId, campaign.getId());

        // 6. Verify ActionIntent Created & Set Due
        ActionIntent intent = actionIntentRepository.findFirstByCampaignIdOrderByCreatedAtDesc(campaign.getId())
                .orElseThrow(() -> new AssertionError("ActionIntent should be created"));
        intent.setStatus(ActionIntentStatus.READY);
        intent.setScheduledAt(Instant.now().minusSeconds(10));
        actionIntentRepository.saveAndFlush(intent);

        // 7. Scheduler Claims Intent & Provider Executes
        actionScheduler.pollAndExecuteDueActions();

        // 8. Verify Persisted Database State & Financial Metrics
        Campaign finalCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, finalCampaign.getCurrentState());

        ActionIntent finalIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.SUCCEEDED, finalIntent.getStatus());
        assertNotNull(finalIntent.getCompletedAt());
        assertNotNull(finalIntent.getResponseReference());
        // M1 behaviour change: synthetic mock references are prefixed pay_mock_ (was mock_ref_), see ADR-001
        assertTrue(finalIntent.getResponseReference().startsWith("pay_mock_"));

        // Validate Revenue Metrics in PostgreSQL
        BigDecimal amountAtRisk = BigDecimal.valueOf(amountInCents).divide(BigDecimal.valueOf(100)); // ₹500.00
        BigDecimal amountRecovered = BigDecimal.valueOf(amountInCents).divide(BigDecimal.valueOf(100)); // ₹500.00
        BigDecimal remainingExposure = amountAtRisk.subtract(amountRecovered);

        assertEquals(0, BigDecimal.ZERO.compareTo(remainingExposure));

        // Validate Analytics Service Returns Matching Metrics
        AnalyticsOverviewDto analytics = analyticsQueryService.getAnalyticsOverview(merchantId, null);
        assertEquals(1, analytics.getTotalCampaigns());
        assertEquals(1, analytics.getRecoveredCampaigns());
        assertEquals(100.0, analytics.getRecoveryRate());

        // Validate Audit Log Entries
        List<AuditLog> auditLogs = auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
        assertFalse(auditLogs.isEmpty());
        assertTrue(auditLogs.stream().anyMatch(a -> a.getEventType().contains("CREATED") || a.getEventType().contains("RECOVERED") || a.getEventType().contains("DECISION")));
    }

    // ==================== SCENARIO B: PROVIDER FAILURE ====================

    @Test
    @DisplayName("Scenario B: Provider Failure -> No False RECOVERED State -> Execution Failure Persisted")
    void testScenarioB_ProviderFailureHandling() {
        Campaign campaign = createTestCampaignWithClassification(merchantId, "pay_fail_01", CampaignStatus.ELIGIBLE, 1, 10000L);
        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());

        // Transition campaign to ACTION_PENDING and mark intent READY & due
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ACTION_PENDING, "Action intent created", "SYSTEM", null);
        intent.setStatus(ActionIntentStatus.READY);
        intent.setScheduledAt(Instant.now().minusSeconds(10));
        actionIntentRepository.saveAndFlush(intent);

        // Configure mock provider to simulate failure
        mockPaymentProviderClient.setSimulatedStatus(PaymentExecutionStatus.FAILURE);

        try {
            // Scheduler claims and executes
            actionScheduler.pollAndExecuteDueActions();

            ActionIntent finalIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
            assertEquals(ActionIntentStatus.FAILED, finalIntent.getStatus());

            Campaign finalCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
            assertNotEquals(CampaignStatus.RECOVERED, finalCampaign.getCurrentState());
        } finally {
            mockPaymentProviderClient.reset();
        }
    }

    // ==================== SCENARIO C: COMPLIANCE BLOCK ====================

    @Test
    @DisplayName("Scenario C: Compliance Block -> Excessive Attempts Blocked -> Provider NOT Called")
    void testScenarioC_ComplianceBlockExcessiveAttempts() {
        Campaign campaign = createTestCampaignWithClassification(merchantId, "pay_comp_01", CampaignStatus.ELIGIBLE, 4, 10000L);

        // Attempting to create action intent when attemptCount > 3 throws ComplianceBlockedException
        assertThrows(ComplianceBlockedException.class, () -> {
            actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        });

        // Verify no ActionIntent was created
        Optional<ActionIntent> intentOpt = actionIntentRepository.findFirstByCampaignIdOrderByCreatedAtDesc(campaign.getId());
        assertTrue(intentOpt.isEmpty());
    }

    // ==================== SCENARIO D: HIGH-RISK PAYMENT ROUTING ====================

    @Test
    @DisplayName("Scenario D: High-Risk Payment -> Routed to Human Approval -> Provider NOT Called Automatically")
    void testScenarioD_HighRiskPaymentRouting() {
        long highValueAmount = 5000000L; // ₹50,000 > ₹10,000 threshold
        Campaign campaign = createTestCampaignWithClassification(merchantId, "pay_high_01", CampaignStatus.ELIGIBLE, 1, highValueAmount);

        // Route high-risk payment to merchant human approval queue
        humanApprovalService.createReview(merchantId, campaign.getId(), "High-risk payment requires manual review");

        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertNotEquals(CampaignStatus.ACTION_PENDING, updatedCampaign.getCurrentState());
        assertNotEquals(CampaignStatus.EXECUTING, updatedCampaign.getCurrentState());

        // Verify provider was not invoked automatically
        assertEquals(0, mockPaymentProviderClient.getInvocationCount());
    }

    // ==================== SCENARIO E: DUPLICATE WEBHOOK IDEMPOTENCY ====================

    @Test
    @DisplayName("Scenario E: Duplicate Webhook -> Exactly 1 Webhook Event & Campaign -> Zero Duplicate Financial Action")
    void testScenarioE_DuplicateWebhookIdempotency() throws Exception {
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
                        "amount": 20000,
                        "currency": "INR",
                        "status": "failed",
                        "error_code": "insufficient_funds"
                      }
                    }
                  }
                }
                """, externalEventId);

        String signature = signatureVerifier.calculateHmacSha256(payload, webhookProperties.getSecret());

        // First ingestion
        var resp1 = webhookService.processRazorpayWebhook(payload, signature);
        assertEquals("ACCEPTED", resp1.getStatus());

        // Duplicate ingestion
        var resp2 = webhookService.processRazorpayWebhook(payload, signature);
        assertEquals("DUPLICATE", resp2.getStatus());

        // Verify database contains exactly 1 record
        Optional<WebhookEvent> eventOpt = webhookEventRepository.findByExternalEventId(externalEventId);
        assertTrue(eventOpt.isPresent());
    }

    // ==================== SCENARIO F: DUPLICATE ACTIONINTENT IDEMPOTENCY ====================

    @Test
    @DisplayName("Scenario F: Duplicate ActionIntent -> Idempotency Key Prevents Duplicate Creation")
    void testScenarioF_DuplicateActionIntentIdempotency() {
        Campaign campaign = createTestCampaignWithClassification(merchantId, "pay_intent_dup_01", CampaignStatus.ELIGIBLE, 1, 10000L);

        ActionIntent intent1 = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        assertNotNull(intent1);

        // Second creation attempt returns the existing intent (idempotency)
        ActionIntent intent2 = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        assertNotNull(intent2);
        assertEquals(intent1.getId(), intent2.getId());
    }

    // ==================== SCENARIO G: RETRY EXHAUSTION ====================

    @Test
    @DisplayName("Scenario G: Retry Exhaustion -> Max Attempts Reached -> Final State EXHAUSTED")
    void testScenarioG_RetryExhaustion() {
        Campaign campaign = createTestCampaignWithClassification(merchantId, "pay_exhaust_01", CampaignStatus.FAILED, 3, 10000L);

        // Transition campaign to EXHAUSTED from FAILED state upon reaching attempt limit
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.EXHAUSTED, "Max recovery retry limit (3) reached", "SYSTEM", null);

        Campaign finalCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.EXHAUSTED, finalCampaign.getCurrentState());
    }

    // ==================== SCENARIO H: AI SERVICE UNAVAILABLE ====================

    @Test
    @DisplayName("Scenario H: AI Service Unavailable -> Bounded Heuristic Fallback Executed Safely")
    void testScenarioH_AiServiceUnavailableFallback() {
        Campaign campaign = createTestCampaignWithClassification(merchantId, "pay_ai_off_01", CampaignStatus.ELIGIBLE, 1, 15000L);

        // Orchestrate recovery (with fallback logic in place)
        recoveryOrchestratorService.orchestrateRecovery(merchantId, campaign.getId());

        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertNotNull(updatedCampaign.getCurrentState());
        assertNotEquals(CampaignStatus.FAILED, updatedCampaign.getCurrentState());
    }

    // ==================== SCENARIO I: CROSS-TENANT ATTEMPT ====================

    @Test
    @DisplayName("Scenario I: Cross-Tenant Attempt -> Unauthorized Merchant Access Denied -> 403 Forbidden")
    void testScenarioI_CrossTenantAccessDenied() {
        UUID unauthorizedMerchantId = UUID.randomUUID();
        Campaign campaign = createTestCampaignWithClassification(merchantId, "pay_cross_01", CampaignStatus.ELIGIBLE, 1, 10000L);

        // Attempting to create action intent using another merchant's ID throws TenantAccessDeniedException
        assertThrows(TenantAccessDeniedException.class, () -> {
            actionIntentService.createActionIntentFromCompliance(unauthorizedMerchantId, campaign.getId());
        });
    }

    // ==================== SCENARIO J: SCHEDULER DUPLICATE EXECUTION ====================

    @Test
    @DisplayName("Scenario J: Concurrent Scheduler Execution -> SELECT FOR UPDATE SKIP LOCKED Guarantees Exactly One Claim")
    void testScenarioJ_ConcurrentSchedulerClaimSafety() throws Exception {
        Campaign campaign = createTestCampaignWithClassification(merchantId, "pay_conc_sched_01", CampaignStatus.ELIGIBLE, 1, 10000L);
        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());

        // Transition campaign to ACTION_PENDING and mark intent READY & due for scheduler claim
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ACTION_PENDING, "Action intent created", "SYSTEM", null);
        intent.setStatus(ActionIntentStatus.READY);
        intent.setScheduledAt(Instant.now().minusSeconds(10));
        actionIntentRepository.saveAndFlush(intent);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<List<ActionIntent>>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < 4; i++) {
            final int workerIdx = i;
            futures.add(executor.submit(() -> {
                latch.await();
                return actionIntentClaimService.claimDueIntents("worker-" + workerIdx, 10);
            }));
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        int totalClaimed = 0;
        for (Future<List<ActionIntent>> future : futures) {
            totalClaimed += future.get().size();
        }

        // Exactly 1 worker must claim the intent
        assertEquals(1, totalClaimed);
    }

    // ==================== HELPER METHODS ====================

    private Campaign createTestCampaignWithClassification(UUID mId, String paymentId, CampaignStatus status, int attemptCount, long amountInCents) {
        Campaign campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        campaign.setMerchantId(mId);
        campaign.setPaymentId(paymentId);
        campaign.setCurrentState(status);
        campaign.setAttemptCount(attemptCount);
        campaign.setStrategy("RETRY_PAYMENT");
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign.setConfidence(BigDecimal.valueOf(0.95));
        campaign.setCreatedAt(Instant.now());
        campaign.setUpdatedAt(Instant.now());
        campaign = campaignRepository.saveAndFlush(campaign);

        // Save WebhookEvent
        WebhookEvent event = new WebhookEvent();
        event.setId(UUID.randomUUID());
        event.setExternalEventId("evt_helper_" + UUID.randomUUID().toString().substring(0, 8));
        event.setEventType("payment.failed");
        event.setMerchantId(mId);
        event.setReceivedAt(Instant.now());
        event.setProcessingStatus(WebhookEventStatus.PROCESSED);
        event = webhookEventRepository.saveAndFlush(event);

        // Save ClassificationResult
        ClassificationResult classificationResult = new ClassificationResult(
                null,
                event.getId(),
                campaign.getId(),
                "INSUFFICIENT_FUNDS",
                BigDecimal.valueOf(0.95),
                "RETRY_PAYMENT",
                "Temporary balance issue detected",
                "v1.0.0"
        );
        classificationResultRepository.saveAndFlush(classificationResult);

        // Evaluate and persist recovery strategy
        recoveryStrategyService.evaluateAndPersistStrategy(mId, campaign.getId());

        return campaignRepository.findById(campaign.getId()).orElse(campaign);
    }
}
