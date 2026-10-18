package com.mend.integration;

import com.mend.testsupport.TestUsers;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.razorpay.RazorpayCredentials;
import com.mend.provider.razorpay.RazorpayProperties;
import com.mend.provider.razorpay.RazorpayStubServer;
import com.mend.service.ActionExecutionService;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.config.RazorpayWebhookProperties;
import com.mend.config.RedisStreamProperties;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.AnalyticsOverviewDto;
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
        "razorpay.webhook.secret=rzp_whsec_phase20",
        "razorpay.single-tenant-merchant-id=" + Phase20_RazorpayTestModeE2EValidationTest.SINGLE_TENANT_MERCHANT
})
@ActiveProfiles("test")
public class Phase20_RazorpayTestModeE2EValidationTest {

    /** The only merchant the single-tenant env credentials may act for (M1 fail-closed resolver). */
    static final String SINGLE_TENANT_MERCHANT = "5f0a9c2e-4b1d-4e8a-9c3f-20a1b2c3d4e5";

    /** M1: the app's Razorpay provider talks to this local stub replaying recorded fixtures, never to the internet. */
    private static final RazorpayStubServer RAZORPAY_STUB = RazorpayStubServer.start();

    @DynamicPropertySource
    static void razorpayStub(DynamicPropertyRegistry registry) {
        registry.add("razorpay.api.base-url", RAZORPAY_STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        RAZORPAY_STUB.close();
    }

    @Autowired
    private ActionExecutionService actionExecutionService;

    @Autowired
    private ComplianceDecisionRepository complianceDecisionRepository;

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

        RAZORPAY_STUB.reset();
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
        // M1 behaviour change: in this test the base URL points at the local fixture stub; the production default is checked instead, see ADR-001
        assertTrue(new RazorpayProperties(new RazorpayProperties.Api(null), "k", "s", 1, 1, null).baseUrl().contains("razorpay.com"));
        assertNotNull(razorpayKeyId);
        assertNotNull(razorpayKeySecret);

        // Secrets must be non-empty in runtime config
        assertFalse(razorpayKeyId.isBlank());
        assertFalse(razorpayKeySecret.isBlank());

        // Sanitization test — secret string must be redacted in logs/messages
        // M1 behaviour change: the legacy client is gone; the credentials record is what reaches logs, see ADR-001
        String printed = new RazorpayCredentials(razorpayKeyId, razorpayKeySecret).toString();
        assertFalse(printed.contains(razorpayKeySecret));
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

        // M3 behaviour change (owner decision B, ADR-003 §4): the reason was in error_code, so the taxonomy saw no
        // documented reason (UNCLASSIFIED), which now gets a payment-link intent at once; the documented Razorpay shape
        // (error_code BAD_REQUEST_ERROR + error_reason) makes this the insufficient-funds failure the test means.
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
                        "error_code": "BAD_REQUEST_ERROR",
                        "error_reason": "insufficient_funds",
                        "error_source": "customer",
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
            classification = com.mend.testsupport.TestClassifications.deterministic(new ClassificationResult(null, savedEvent.getId(), campaign.getId(), "INSUFFICIENT_FUNDS", BigDecimal.valueOf(0.95), "RETRY_PAYMENT", "Balance issue", "v1.0.0"));
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
        com.mend.testsupport.TestClassifications.saveFor(classificationResultRepository, campaign);   // M3: deterministic row (ADR-003 §4)
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible for recovery", "SYSTEM", null);
        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());

        // Step 5: Agent Orchestration -> ActionIntent
        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        assertNotNull(intent);

        // Step 6 (M1 behaviour change): POST /v1/payments/{id}/retry does not exist in Razorpay (fixture 10 recorded a 404).
        // RETRY_PAYMENT in razorpay mode is NotSupported locally: zero HTTP calls, intent FAILED. See ADR-001.
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ACTION_PENDING, "Action pending execution", "SYSTEM", null);
        ActionIntent claimed = claim(intent);
        // M2 behaviour change: the executor requires the caller's claim token (fail closed), see ADR-002
        var report = actionExecutionService.executeActionIntent(claimed.getId(), "worker-phase20", claimed.getClaimToken());

        assertEquals(PaymentExecutionStatus.FAILURE, report.getStatus());
        assertEquals("PROVIDER_OPERATION_NOT_SUPPORTED", report.getResponseCode());
        assertTrue(RAZORPAY_STUB.requests().isEmpty(), "RETRY_PAYMENT must make zero HTTP calls in razorpay mode");
        assertEquals(ActionIntentStatus.FAILED, actionIntentRepository.findById(claimed.getId()).orElseThrow().getStatus());
        assertTrue(auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(campaign.getId()).stream()
                .anyMatch(a -> "PROVIDER_REQUEST_REJECTED".equals(a.getEventType())
                        && a.getReason().contains("RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2")));

        // Step 7 (M1 behaviour change): no request outcome may set RECOVERED; the campaign is exhausted, not recovered.
        Campaign finalCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertNotEquals(CampaignStatus.RECOVERED, finalCampaign.getCurrentState());
        // M2 behaviour change: a debit that cannot run automatically goes to human review (campaign ELIGIBLE), it no longer
        // exhausts the campaign, see ADR-002 decision 10
        assertEquals(CampaignStatus.ELIGIBLE, finalCampaign.getCurrentState());
        assertNotEquals(CampaignStatus.EXHAUSTED, finalCampaign.getCurrentState());

        // Step 8: Revenue Metrics Verification
        // Bug sweep behaviour change (item 1): user-facing service methods now require a user (TenantGuard);
        // these calls pass a member of the merchant, as every production caller does. Assertions unchanged.
        AnalyticsOverviewDto analytics = analyticsQueryService.getAnalyticsOverview(merchantId, TestUsers.memberOf(merchantId));
        assertEquals(1, analytics.getTotalCampaigns());
        // M1 behaviour change: nothing was recovered without captured evidence, see ADR-001
        assertEquals(0, analytics.getRecoveredCampaigns());
        assertEquals(0.0, analytics.getRecoveryRate());
    }

    // ==================== SCENARIO 4: RAZORPAY IDEMPOTENCY ====================

    @Test
    @DisplayName("4. Razorpay Idempotency — Headers and Idempotency Key Preservation")
    void testRazorpayIdempotencyHeaders() throws Exception {
        // M1 behaviour change: REQUEST_CUSTOMER_ACTION creates a payment link (recorded fixture 01) through the executor;
        // the attempt key travels as reference_id (no idempotency headers exist for links); the intent stays EXECUTING
        // and the campaign is NOT recovered. See ADR-001.
        LinkFixture fixture = claimedLinkIntent("pay_rzp_link_01", 100);
        // Fixture 01 replayed with reference_id echoed from the request: the recorded reference belongs to the recording run.
        String fixture01 = recordedBody("01_create_link_ok");
        RAZORPAY_STUB.stub("POST", "/v1/payment_links", 200, requestBody ->
                fixture01.replace("mnd_rec_1791176340", readJson(requestBody).get("reference_id").asString()));

        var report = actionExecutionService.executeActionIntent(fixture.intent().getId(), "worker-phase20", fixture.intent().getClaimToken());

        assertEquals(PaymentExecutionStatus.PENDING, report.getStatus());
        assertEquals("plink_Tk6TAI6WoeOTDh", report.getExternalReference());
        ActionIntent storedIntent = actionIntentRepository.findById(fixture.intent().getId()).orElseThrow();
        // M2 behaviour change: a live payment link is AWAITING_PAYMENT (was EXECUTING), see ADR-002
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, storedIntent.getStatus());
        assertEquals("plink_Tk6TAI6WoeOTDh", storedIntent.getResponseReference());
        Campaign storedCampaign = campaignRepository.findById(fixture.campaign().getId()).orElseThrow();
        assertNotEquals(CampaignStatus.RECOVERED, storedCampaign.getCurrentState());
        assertEquals(CampaignStatus.EXECUTING, storedCampaign.getCurrentState());

        assertEquals(1, RAZORPAY_STUB.requests().size());
        RazorpayStubServer.Request sent = RAZORPAY_STUB.requests().get(0);
        var body = readJson(sent.body());
        assertEquals(ProviderAttemptKey.forIntent(fixture.intent().getId()).value(), body.get("reference_id").asString());
        assertEquals(100L, body.get("amount").longValue());
        assertEquals(fixture.intent().getId().toString(), body.get("notes").get("mend_intent_id").asString());
        assertTrue(sent.headers().keySet().stream().noneMatch(h -> h.equalsIgnoreCase("X-Razorpay-Idempotency-Header")));
        assertTrue(sent.headers().keySet().stream().noneMatch(h -> h.equalsIgnoreCase("X-Idempotency-Key")));
        assertTrue(auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(fixture.campaign().getId()).stream()
                .anyMatch(a -> "PROVIDER_REQUEST_ACCEPTED".equals(a.getEventType())));
    }

    // ==================== SCENARIO 5: PROVIDER 4XX DECLINE ====================

    @Test
    @DisplayName("5. Razorpay Provider 4xx — Card Declined Execution Result Mapping")
    void testRazorpayDeclineHandling() throws Exception {
        // M1 behaviour change: a 400 on create link (recorded fixture 03) is resolved by lookup-by-reference first;
        // no existing link -> Rejected(VALIDATION) -> intent FAILED, campaign EXHAUSTED. See ADR-001.
        LinkFixture fixture = claimedLinkIntent("pay_rzp_dec_01", 10000);
        String key = ProviderAttemptKey.forIntent(fixture.intent().getId()).value();
        RAZORPAY_STUB.stub("POST", "/v1/payment_links", 400, recordedBody("03_create_link_extra_fields"));
        RAZORPAY_STUB.stub("GET", "/v1/payment_links?reference_id=" + key, 200, "{\"payment_links\":[]}");

        var report = actionExecutionService.executeActionIntent(fixture.intent().getId(), "worker-phase20", fixture.intent().getClaimToken());

        assertEquals(PaymentExecutionStatus.FAILURE, report.getStatus());
        assertEquals("BAD_REQUEST_ERROR", report.getResponseCode());
        assertEquals("extra fields sent", report.getMessage());
        assertEquals(ActionIntentStatus.FAILED, actionIntentRepository.findById(fixture.intent().getId()).orElseThrow().getStatus());
        assertEquals(CampaignStatus.EXHAUSTED, campaignRepository.findById(fixture.campaign().getId()).orElseThrow().getCurrentState());
        assertEquals(List.of("POST", "GET"), RAZORPAY_STUB.requests().stream().map(RazorpayStubServer.Request::method).toList());
        assertTrue(auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(fixture.campaign().getId()).stream()
                .anyMatch(a -> "PROVIDER_REQUEST_REJECTED".equals(a.getEventType()) && a.getReason().contains("VALIDATION")));
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
        Campaign saved = campaignRepository.saveAndFlush(campaign);
        com.mend.testsupport.TestClassifications.saveFor(classificationResultRepository, saved);   // M3: deterministic row (ADR-003 §4)
        return saved;
    }

    private record LinkFixture(Campaign campaign, ActionIntent intent) {
    }

    /** Campaign for the single-tenant merchant, its original payment webhook (amount source) and a CLAIMED link intent. */
    private LinkFixture claimedLinkIntent(String paymentId, long amountMinor) {
        UUID tenant = UUID.fromString(SINGLE_TENANT_MERCHANT);
        if (!merchantRepository.existsById(tenant)) {
            merchantRepository.saveAndFlush(new Merchant(tenant, "Phase20 Single-Tenant Merchant"));
        }
        String uniquePaymentId = paymentId + "_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), uniquePaymentId, "payment.failed", "RAZORPAY", String.format(
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"%s\",\"amount\":%d,\"currency\":\"INR\",\"status\":\"failed\"}}}}",
                uniquePaymentId, amountMinor));
        event.setMerchantId(tenant);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhookEventRepository.saveAndFlush(event);

        Campaign campaign = createTestCampaign(tenant, uniquePaymentId, CampaignStatus.ACTION_PENDING, 0, amountMinor);
        ComplianceDecisionEntity compliance = complianceDecisionRepository.saveAndFlush(new ComplianceDecisionEntity(
                UUID.randomUUID(), campaign.getId(), tenant, null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED,
                // M3 behaviour change: was a v1.x version, which is now LEGACY_DECISION at dispatch (see TestCompliance)
                ComplianceStatus.COMPLIANCE_ALLOWED, ComplianceReason.ALLOWED, "Compliant action", com.mend.testsupport.TestCompliance.CURRENT_POLICY));
        ActionIntent intent = new ActionIntent(UUID.randomUUID(), tenant, campaign.getId(), 1,
                ActionType.REQUEST_CUSTOMER_ACTION.name(), RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), compliance.getId(),
                ActionIntentStatus.CLAIMED, "intent:" + campaign.getId() + ":attempt_1:REQUEST_CUSTOMER_ACTION", Instant.now());
        intent.setClaimedAt(Instant.now());
        intent.setWorkerId("worker-phase20");
        // M2: a CLAIMED intent always carries a claim token (fixture correction; the dispatch fence requires it), see ADR-002
        intent.setClaimToken(UUID.randomUUID().toString());
        return new LinkFixture(campaign, actionIntentRepository.saveAndFlush(intent));
    }

    private ActionIntent claim(ActionIntent intent) {
        ActionIntent stored = actionIntentRepository.findById(intent.getId()).orElseThrow();
        stored.setStatus(ActionIntentStatus.CLAIMED);
        stored.setClaimedAt(Instant.now());
        stored.setWorkerId("worker-phase20");
        // M2: a CLAIMED intent always carries a claim token (fixture correction; the dispatch fence requires it), see ADR-002
        stored.setClaimToken(UUID.randomUUID().toString());
        return actionIntentRepository.saveAndFlush(stored);
    }

    private static String recordedBody(String fixture) throws Exception {
        try (var in = Phase20_RazorpayTestModeE2EValidationTest.class.getResourceAsStream("/razorpay/recorded/" + fixture + ".json")) {
            var mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(mapper.readTree(in).path("response").path("body"));
        }
    }

    private static tools.jackson.databind.JsonNode readJson(String body) {
        return new tools.jackson.databind.ObjectMapper().readTree(body);
    }

    // ---- Bug sweep item 5: SYNTHETIC attribution is impossible in razorpay mode ----

    @Autowired
    private org.springframework.context.ApplicationContext bugSweepContext;

    @Test
    @DisplayName("Bug sweep item 5: razorpay mode has no synthetic simulator and refuses a SYNTHETIC recovery")
    void syntheticAttributionIsImpossibleInRazorpayMode() {
        assertTrue(bugSweepContext.getBeansOfType(com.mend.provider.mock.SyntheticDebitSimulator.class).isEmpty(),
                "no SyntheticDebitSimulator, so the dispatcher can never produce SyntheticCaptured");

        UUID merchant = UUID.randomUUID();
        merchantRepository.save(new Merchant(merchant, "BS5 razorpay merchant"));
        Campaign campaign = new Campaign(UUID.randomUUID(), merchant);
        campaign.setPaymentId("pay_bs5rzp" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        campaign.setCurrentState(CampaignStatus.EXECUTING);
        UUID campaignId = campaignRepository.save(campaign).getId();

        assertThrows(IllegalStateException.class, () -> campaignLifecycleService.recover(merchant, campaignId,
                RecoveryAttribution.SYNTHETIC, "should never happen", "SYSTEM", null));

        Campaign after = campaignRepository.findById(campaignId).orElseThrow();
        assertEquals(CampaignStatus.EXECUTING, after.getCurrentState());
        assertNull(after.getRecoveryAttribution());
    }
}
