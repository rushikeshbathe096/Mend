package com.mend.integration;

import com.mend.client.AiClassificationClient;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.scheduler.ActionScheduler;
import com.mend.service.*;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.ProviderFailure;
import com.mend.testsupport.TestRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * M3 U2.2 behaviour change (ADR-003): this class replaces AiRecoveryAgentIntegrationTest (same 11 scenarios). The payment
 * path classifies with the deterministic Razorpay taxonomy: no AI call, no confidence, no model version, no fabricated
 * fallback. The AI client stays mocked only to prove it is never touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class DeterministicRecoveryPathIntegrationTest {

    @Autowired private ClassificationService classificationService;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private RecoveryOrchestratorService recoveryOrchestratorService;
    @Autowired private ActionScheduler actionScheduler;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private ClassificationResultRepository classificationResultRepository;
    @Autowired private ActionIntentRepository actionIntentRepository;
    @Autowired private CampaignAttemptRepository campaignAttemptRepository;
    @Autowired private ComplianceDecisionRepository complianceDecisionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean
    private AiClassificationClient aiClassificationClient;

    private Merchant merchantA;
    private Merchant merchantB;

    @BeforeEach
    void setUp() {
        // Own rows only (shared database); the scheduler polls across merchants, so other classes' claimable rows are parked.
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantA = merchantRepository.save(new Merchant(UUID.randomUUID(), "Merchant Alpha"));
        merchantB = merchantRepository.save(new Merchant(UUID.randomUUID(), "Merchant Beta"));
    }

    @AfterEach
    void tearDown() {
        verifyNoInteractions(aiClassificationClient);   // the payment path never calls the AI service (ADR-003 §2)
        TestRows.deleteMerchant(jdbc, merchantA.getId());
        TestRows.deleteMerchant(jdbc, merchantB.getId());
    }

    private WebhookEvent failedEvent(String paymentId, String reason, String source) {
        String errorFields = (reason == null ? "" : ",\"error_reason\":\"" + reason + "\"")
                + (source == null ? "" : ",\"error_source\":\"" + source + "\"");
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\",\"error_code\":\"BAD_REQUEST_ERROR\""
                        + errorFields + "}}}}");
        event.setMerchantId(merchantA.getId());
        return webhookEventRepository.save(event);
    }

    private static String pay(String prefix) {
        return "pay_" + prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @Test
    @DisplayName("1. Documented reason: insufficient_funds -> INSUFFICIENT_FUNDS (no confidence, no model) -> ActionIntent created")
    void documentedReasonCreatesIntent() {
        String paymentId = pay("d1");
        WebhookEvent event = failedEvent(paymentId, "insufficient_funds", "customer");

        ClassificationResult result = classificationService.classifyAndPersist(event,
                ProviderFailure.of("BAD_REQUEST_ERROR", "insufficient_funds", "customer"));

        assertEquals("INSUFFICIENT_FUNDS", result.getFailureClass());
        assertNull(result.getConfidence(), "a deterministic class has no confidence");
        assertNull(result.getModelVersion(), "no model produced it");
        assertEquals(FailureTaxonomy.TABLE_VERSION, result.getTaxonomyVersion());
        assertEquals("REASON", result.getEvidence().get("matchedOn"));
        assertEquals(FailureTaxonomy.REASONS_DOC, result.getEvidence().get("documentation"));

        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), paymentId).orElseThrow();
        assertEquals(CampaignStatus.ACTION_PENDING, campaign.getCurrentState());
        assertNull(campaign.getConfidence());
        List<ActionIntent> intents = actionIntentRepository.findByCampaignId(campaign.getId());
        assertEquals(1, intents.size());
        assertTrue(intents.get(0).getStatus() == ActionIntentStatus.SCHEDULED || intents.get(0).getStatus() == ActionIntentStatus.READY);
    }

    @Test
    @DisplayName("2. Unmapped reason (was: malformed AI response) -> UNCLASSIFIED -> campaign EXHAUSTED, no intent")
    void unmappedReasonIsUnclassified() {
        String paymentId = pay("d2");
        WebhookEvent event = failedEvent(paymentId, "bad_code", "customer");

        ClassificationResult result = classificationService.classifyAndPersist(event, ProviderFailure.of(null, "bad_code", "customer"));

        assertEquals("UNCLASSIFIED", result.getFailureClass());
        assertNull(result.getConfidence());
        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), paymentId).orElseThrow();
        assertEquals(CampaignStatus.EXHAUSTED, campaign.getCurrentState());
        assertTrue(actionIntentRepository.findByCampaignId(campaign.getId()).isEmpty());
    }

    @Test
    @DisplayName("3. No confidence gate (was: low AI confidence -> EXHAUSTED): a documented class with no confidence is eligible")
    void noConfidenceGate() {
        String paymentId = pay("d3");
        WebhookEvent event = failedEvent(paymentId, "card_expired", "customer");

        ClassificationResult result = classificationService.classifyAndPersist(event, ProviderFailure.of(null, "card_expired", "customer"));

        assertEquals("INSTRUMENT_INVALID", result.getFailureClass());
        assertNull(result.getConfidence());
        assertTrue(campaignLifecycleService.evaluateEligibility(result));
        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), paymentId).orElseThrow();
        assertEquals(CampaignStatus.ACTION_PENDING, campaign.getCurrentState());
    }

    @Test
    @DisplayName("4. No AI timeout dependency (was: AI timeout fallback): classification never waits on or calls the AI service")
    void noAiTimeoutDependency() {
        String paymentId = pay("d4");
        WebhookEvent event = failedEvent(paymentId, "request_timed_out", "gateway");

        ClassificationResult result = classificationService.classifyAndPersist(event, ProviderFailure.of(null, "request_timed_out", "gateway"));

        assertEquals("GATEWAY_ERROR", result.getFailureClass());
        assertFalse(result.getReasoning().contains("AI"), result.getReasoning());
        // the @AfterEach asserts that the AI client was never called
    }

    @Test
    @DisplayName("5. Audit (was: AI unavailable fallback audit): FAILURE_CLASSIFIED with the provider fields and documentation")
    void classificationIsAudited() {
        String paymentId = pay("d5");
        WebhookEvent event = failedEvent(paymentId, null, "gateway");

        ClassificationResult result = classificationService.classifyAndPersist(event, ProviderFailure.of("GATEWAY_ERROR", null, "gateway"));

        assertEquals("GATEWAY_ERROR", result.getFailureClass());
        assertEquals("SOURCE", result.getEvidence().get("matchedOn"));
        List<AuditLog> auditLogs = auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantA.getId());
        assertThat(auditLogs).anyMatch(log -> "FAILURE_CLASSIFIED".equals(log.getEventType()));
        assertThat(auditLogs).noneMatch(log -> "AI_CLASSIFICATION_COMPLETED".equals(log.getEventType()));
    }

    @Test
    @DisplayName("6. Compliance rejection (was: low confidence block): a legacy, non-taxonomy class is blocked, no intent")
    void complianceBlocksLegacyClass() {
        WebhookEvent event = failedEvent("evt_det_006_" + UUID.randomUUID(), "insufficient_funds", "customer");
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantA.getId(), event.getExternalEventId(), "cust_hash", "sub_123");
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaign.setStrategy("RETRY_IMMEDIATELY");
        campaign.setAttemptCount(1);
        campaign = campaignRepository.saveAndFlush(campaign);
        // a historical, AI-era row: pre-M3 class with an AI confidence (kept as it was, never rewritten)
        classificationResultRepository.saveAndFlush(new ClassificationResult(UUID.randomUUID(), event.getId(), campaign.getId(),
                "BANK_DECLINED", new java.math.BigDecimal("0.95"), "RETRY_IMMEDIATELY", "AI-era reasoning", "v1.0"));

        ActionIntent intent = recoveryOrchestratorService.orchestrateRecovery(merchantA.getId(), campaign.getId());

        assertNull(intent, "a legacy class fails closed");
        List<ComplianceDecisionEntity> decisions = complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
        assertFalse(decisions.isEmpty());
        assertEquals(ComplianceStatus.COMPLIANCE_BLOCKED, decisions.get(0).getStatus());
        // a legacy class gets MANUAL_REVIEW from the strategy table, which compliance blocks as non-executable; were it
        // approved, the re-check would block it as UNKNOWN_CLASSIFICATION (ComplianceEngineTest 5b)
        assertEquals(ComplianceReason.STRATEGY_NOT_SUPPORTED, decisions.get(0).getReason());
    }

    @Test
    @DisplayName("7. Legacy classes fail closed (was: AI-failure deterministic fallback): ineligible, never re-labelled")
    void legacyClassesAreIneligible() {
        for (String legacy : List.of("UNKNOWN", "BANK_DECLINED", "NETWORK_FAILURE", "CARD_EXPIRED")) {
            ClassificationResult row = new ClassificationResult(UUID.randomUUID(), null, null, legacy, new java.math.BigDecimal("0.99"),
                    null, null, "v1.0.0-bounded-heuristic");
            assertFalse(campaignLifecycleService.evaluateEligibility(row), legacy + " must be ineligible");
        }
        ClassificationResult unclassified = new ClassificationResult(UUID.randomUUID(), null, null, "UNCLASSIFIED", null, null, null, null);
        assertFalse(campaignLifecycleService.evaluateEligibility(unclassified));
    }

    @Test
    @DisplayName("8. Duplicate event idempotency: classifying the same event twice returns the same row, no second campaign")
    void duplicateEventIdempotency() {
        String paymentId = pay("d8");
        WebhookEvent event = failedEvent(paymentId, "insufficient_funds", "customer");
        ProviderFailure failure = ProviderFailure.of(null, "insufficient_funds", "customer");

        ClassificationResult firstResult = classificationService.classifyAndPersist(event, failure);
        ClassificationResult secondResult = classificationService.classifyAndPersist(event, failure);

        assertEquals(firstResult.getId(), secondResult.getId());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM classification_results WHERE event_id = ?", Long.class, event.getId()));
        assertTrue(campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), paymentId).isPresent());
    }

    @Test
    @DisplayName("9. Tenant Isolation: Merchant B cannot access or orchestrate Merchant A's campaigns")
    void tenantIsolation() {
        Campaign campaignA = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaignA.setPaymentId("pay_tenant_a_" + UUID.randomUUID());
        campaignA.setCurrentState(CampaignStatus.ELIGIBLE);
        campaignA = campaignRepository.save(campaignA);

        final UUID campaignId = campaignA.getId();

        assertThrows(TenantAccessDeniedException.class, () ->
                recoveryOrchestratorService.orchestrateRecovery(merchantB.getId(), campaignId)
        );
    }

    @Test
    @DisplayName("10. Untrusted reason text (was: prohibited action proposal): 'bypass_safety_direct_charge' -> UNCLASSIFIED, EXHAUSTED")
    void untrustedReasonTextIsNeverActedOn() {
        String paymentId = pay("d10");
        WebhookEvent event = failedEvent(paymentId, "bypass_safety_direct_charge", "customer");

        ClassificationResult result = classificationService.classifyAndPersist(event,
                ProviderFailure.of(null, "bypass_safety_direct_charge", "customer"));

        assertEquals("UNCLASSIFIED", result.getFailureClass());
        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), paymentId).orElseThrow();
        assertEquals(CampaignStatus.EXHAUSTED, campaign.getCurrentState());
        assertTrue(actionIntentRepository.findByCampaignId(campaign.getId()).isEmpty());
    }

    @Test
    @DisplayName("11. Complete path: failed payment -> taxonomy -> strategy -> compliance -> action -> provider -> outcome")
    void completeExecutionPath() {
        String paymentId = pay("d11");
        WebhookEvent event = failedEvent(paymentId, "insufficient_funds", "customer");

        ClassificationResult classification = classificationService.classifyAndPersist(event,
                ProviderFailure.of("BAD_REQUEST_ERROR", "insufficient_funds", "customer"));
        assertEquals("INSUFFICIENT_FUNDS", classification.getFailureClass());

        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), paymentId).orElseThrow();
        assertEquals(CampaignStatus.ACTION_PENDING, campaign.getCurrentState());

        List<ActionIntent> intents = actionIntentRepository.findByCampaignId(campaign.getId());
        assertEquals(1, intents.size());
        ActionIntent intent = intents.get(0);
        assertTrue(intent.getStatus() == ActionIntentStatus.SCHEDULED || intent.getStatus() == ActionIntentStatus.READY);

        intent.setStatus(ActionIntentStatus.READY);
        intent.setScheduledAt(Instant.now().minusSeconds(10));
        actionIntentRepository.save(intent);

        actionScheduler.pollAndExecuteDueActions();

        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.SUCCEEDED, updatedIntent.getStatus());
        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, updatedCampaign.getCurrentState());

        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        assertEquals("SUCCESS", attempts.get(0).getStatus());

        List<AuditLog> auditLogs = auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantA.getId());
        assertThat(auditLogs).anyMatch(log -> "FAILURE_CLASSIFIED".equals(log.getEventType()));
        assertThat(auditLogs).anyMatch(log -> "ACTION_EXECUTED".equals(log.getEventType()) || "ACTION_EXECUTING".equals(log.getEventType())
                || "ACTION_SUCCEEDED".equals(log.getEventType()));
    }
}
