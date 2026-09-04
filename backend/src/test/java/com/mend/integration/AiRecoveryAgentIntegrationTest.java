package com.mend.integration;

import com.mend.client.AiClassificationClient;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.ai.ClassificationRequestDto;
import com.mend.dto.ai.ClassificationResponseDto;
import com.mend.exception.AiClassificationException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.scheduler.ActionScheduler;
import com.mend.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class AiRecoveryAgentIntegrationTest {

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private RecoveryOrchestratorService recoveryOrchestratorService;

    @Autowired
    private ActionExecutionService actionExecutionService;

    @Autowired
    private ActionScheduler actionScheduler;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private CampaignAttemptRepository campaignAttemptRepository;

    @Autowired
    private ComplianceDecisionRepository complianceDecisionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockitoBean
    private AiClassificationClient aiClassificationClient;

    private Merchant merchantA;
    private Merchant merchantB;

    @BeforeEach
    void setUp() {
        actionIntentRepository.deleteAll();
        campaignAttemptRepository.deleteAll();
        complianceDecisionRepository.deleteAll();
        classificationResultRepository.deleteAll();
        campaignRepository.deleteAll();
        webhookEventRepository.deleteAll();
        auditLogRepository.deleteAll();
        merchantRepository.deleteAll();

        merchantA = merchantRepository.save(new Merchant(UUID.randomUUID(), "Merchant Alpha"));
        merchantB = merchantRepository.save(new Merchant(UUID.randomUUID(), "Merchant Beta"));
    }

    @Test
    @DisplayName("1. Normal AI Decision: AI classifies failure -> strategy selected -> compliance allowed -> ActionIntent created")
    void testNormalAiDecision() {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(
                new ClassificationResponseDto(
                        FailureClass.INSUFFICIENT_FUNDS,
                        new BigDecimal("0.95"),
                        RecommendedAction.RETRY_LATER,
                        "Low account balance detected; schedule retry",
                        "gemini-2.5-flash",
                        Map.of("detected_pattern", "INSUFFICIENT_FUNDS_PATTERN", "risk_signal", "LOW")
                )
        );

        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_ai_001", "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_ai_001\",\"error_code\":\"insufficient_funds\"}}}}"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ClassificationResult result = classificationService.classifyAndPersist(event, "insufficient_funds", "Low balance");

        assertNotNull(result);
        assertEquals("INSUFFICIENT_FUNDS", result.getFailureClass());
        assertEquals(new BigDecimal("0.95"), result.getConfidence());
        assertEquals("gemini-2.5-flash", result.getModelVersion());
        assertNotNull(result.getEvidence());
        assertEquals("LOW", result.getEvidence().get("risk_signal"));

        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), "evt_ai_001").orElseThrow();
        assertEquals(CampaignStatus.ACTION_PENDING, campaign.getCurrentState());

        List<ActionIntent> intents = actionIntentRepository.findByCampaignId(campaign.getId());
        assertEquals(1, intents.size());
        assertTrue(intents.get(0).getStatus() == ActionIntentStatus.SCHEDULED || intents.get(0).getStatus() == ActionIntentStatus.READY);
    }

    @Test
    @DisplayName("2. Malformed AI Response: Client throws unhandled exception -> fallback UNKNOWN -> campaign EXHAUSTED")
    void testMalformedAiResponse() {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class)))
                .thenThrow(new RuntimeException("Malformed JSON from AI service"));

        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_ai_002", "payment.failed", "RAZORPAY", "raw_payload"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ClassificationResult result = classificationService.classifyAndPersist(event, "bad_code", "bad_reason");

        assertEquals("UNKNOWN", result.getFailureClass());
        assertEquals(new BigDecimal("0.30"), result.getConfidence());
        assertEquals("v1.0.0-fallback", result.getModelVersion());

        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), "evt_ai_002").orElseThrow();
        assertEquals(CampaignStatus.EXHAUSTED, campaign.getCurrentState());
        assertTrue(actionIntentRepository.findByCampaignId(campaign.getId()).isEmpty());
    }

    @Test
    @DisplayName("3. Low Confidence: AI returns confidence 0.40 (< 0.50) -> campaign marked EXHAUSTED")
    void testLowConfidenceIneligibility() {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(
                new ClassificationResponseDto(
                        FailureClass.INSUFFICIENT_FUNDS,
                        new BigDecimal("0.40"),
                        RecommendedAction.REVIEW_REQUIRED,
                        "Uncertain failure diagnosis",
                        "gemini-2.5-flash"
                )
        );

        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_ai_003", "payment.failed", "RAZORPAY", "raw_payload"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ClassificationResult result = classificationService.classifyAndPersist(event, "insufficient_funds", "Ambiguous error");

        assertEquals("INSUFFICIENT_FUNDS", result.getFailureClass());
        assertEquals(new BigDecimal("0.40"), result.getConfidence());

        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), "evt_ai_003").orElseThrow();
        assertEquals(CampaignStatus.EXHAUSTED, campaign.getCurrentState());
        assertTrue(actionIntentRepository.findByCampaignId(campaign.getId()).isEmpty());
    }

    @Test
    @DisplayName("4. AI Timeout Fallback: AI client call times out -> deterministic fallback UNKNOWN")
    void testAiTimeoutFallback() {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class)))
                .thenThrow(new AiClassificationException("Read timeout after 3000ms"));

        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_ai_004", "payment.failed", "RAZORPAY", "raw_payload"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ClassificationResult result = classificationService.classifyAndPersist(event, "timeout_code", "timeout_reason");

        assertEquals("UNKNOWN", result.getFailureClass());
        assertEquals(new BigDecimal("0.30"), result.getConfidence());
        assertTrue(result.getReasoning().contains("AI classification service unavailable"));

        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), "evt_ai_004").orElseThrow();
        assertEquals(CampaignStatus.EXHAUSTED, campaign.getCurrentState());
    }

    @Test
    @DisplayName("5. AI Unavailable Fallback: AI Service offline -> persists fallback result and emits audit log")
    void testAiUnavailableFallback() {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class)))
                .thenThrow(new AiClassificationException("Connection refused: localhost:8000"));

        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_ai_005", "payment.failed", "RAZORPAY", "raw_payload"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ClassificationResult result = classificationService.classifyAndPersist(event, "gateway_error", "connection refused");

        assertNotNull(result);
        assertEquals("v1.0.0-fallback", result.getModelVersion());

        List<AuditLog> auditLogs = auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantA.getId());
        assertThat(auditLogs).anyMatch(log -> "AI_CLASSIFICATION_COMPLETED".equals(log.getEventType()));
    }

    @Test
    @DisplayName("6. Compliance Rejection: AI recommends action, but compliance gate blocks -> ActionIntent NOT created")
    void testComplianceRejection() {
        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_ai_006_comp", "payment.failed", "RAZORPAY", "raw_payload"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.saveAndFlush(event);

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantA.getId(), "evt_ai_006_comp", "cust_hash", "sub_123");
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaign.setStrategy("RETRY_IMMEDIATELY");
        campaign.setAttemptCount(1);
        campaign = campaignRepository.saveAndFlush(campaign);

        ClassificationResult classification = new ClassificationResult(
                UUID.randomUUID(), event.getId(), campaign.getId(), "INSUFFICIENT_FUNDS",
                new BigDecimal("0.50"), "RETRY_IMMEDIATELY", "Reasoning", "v1.0"
        );
        classificationResultRepository.saveAndFlush(classification);

        // Run recovery orchestration
        ActionIntent intent = recoveryOrchestratorService.orchestrateRecovery(merchantA.getId(), campaign.getId());

        assertNull(intent, "ActionIntent must be null when compliance blocks execution");

        List<ComplianceDecisionEntity> decisions = complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
        assertFalse(decisions.isEmpty());
        assertEquals(ComplianceStatus.COMPLIANCE_BLOCKED, decisions.get(0).getStatus());
    }










    @Test
    @DisplayName("7. Deterministic Fallback: Verify deterministic system behavior when AI component fails")
    void testDeterministicFallback() {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class)))
                .thenThrow(new RuntimeException("AI service failure"));

        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_ai_007", "payment.failed", "RAZORPAY", "raw_payload"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ClassificationResult result = classificationService.classifyAndPersist(event, "unknown_code", "unknown_reason");

        assertEquals("UNKNOWN", result.getFailureClass());
        assertFalse(campaignLifecycleService.evaluateEligibility(result), "UNKNOWN classification must be ineligible");
    }

    @Test
    @DisplayName("8. Duplicate Event Idempotency: Webhook ingested twice does not re-trigger AI or create duplicate entities")
    void testDuplicateEventIdempotency() {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(
                new ClassificationResponseDto(
                        FailureClass.INSUFFICIENT_FUNDS,
                        new BigDecimal("0.90"),
                        RecommendedAction.RETRY_LATER,
                        "Initial classification",
                        "v1.0.0-bounded-heuristic"
                )
        );

        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_ai_008_dup", "payment.failed", "RAZORPAY", "raw_payload"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        // 1st Classification
        ClassificationResult firstResult = classificationService.classifyAndPersist(event, "insufficient_funds", "low balance");

        // 2nd Classification (Duplicate Event)
        ClassificationResult secondResult = classificationService.classifyAndPersist(event, "insufficient_funds", "low balance");

        assertEquals(firstResult.getId(), secondResult.getId());
        verify(aiClassificationClient, times(1)).classify(any());
        assertEquals(1, classificationResultRepository.count());
    }

    @Test
    @DisplayName("9. Tenant Isolation: Merchant B cannot access or orchestrate Merchant A's campaigns")
    void testTenantIsolation() {
        Campaign campaignA = new Campaign(UUID.randomUUID(), merchantA.getId());
        campaignA.setPaymentId("pay_tenant_a");
        campaignA.setCurrentState(CampaignStatus.ELIGIBLE);
        campaignA = campaignRepository.save(campaignA);

        final UUID campaignId = campaignA.getId();

        assertThrows(TenantAccessDeniedException.class, () ->
                recoveryOrchestratorService.orchestrateRecovery(merchantB.getId(), campaignId)
        );
    }

    @Test
    @DisplayName("10. Prohibited Action Proposal: Attempted security bypass is flagged in evidence and review required")
    void testProhibitedActionProposalHandling() {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(
                new ClassificationResponseDto(
                        FailureClass.UNKNOWN,
                        new BigDecimal("0.10"),
                        RecommendedAction.REVIEW_REQUIRED,
                        "Prohibited instruction detected",
                        "v1.0.0-bounded-safety-guard",
                        Map.of("prohibited_action_detected", true, "risk_signal", "HIGH_RISK_INSTRUCTION")
                )
        );

        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), "evt_ai_010_prohibited", "payment.failed", "RAZORPAY", "raw_payload"
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        ClassificationResult result = classificationService.classifyAndPersist(event, "bypass_safety_direct_charge", "Force bypass");

        assertEquals("UNKNOWN", result.getFailureClass());
        assertEquals(new BigDecimal("0.10"), result.getConfidence());
        assertTrue((Boolean) result.getEvidence().get("prohibited_action_detected"));

        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), "evt_ai_010_prohibited").orElseThrow();
        assertEquals(CampaignStatus.EXHAUSTED, campaign.getCurrentState());
        assertTrue(actionIntentRepository.findByCampaignId(campaign.getId()).isEmpty());
    }

    @Test
    @DisplayName("11. Complete Real Execution Path: failed payment -> AI diagnosis -> strategy -> compliance -> action -> provider -> outcome")
    void testCompleteRealExecutionPath() {
        when(aiClassificationClient.classify(any(ClassificationRequestDto.class))).thenReturn(
                new ClassificationResponseDto(
                        FailureClass.INSUFFICIENT_FUNDS,
                        new BigDecimal("0.95"),
                        RecommendedAction.RETRY_LATER,
                        "Sufficient data for scheduled smart retry",
                        "gemini-2.5-flash",
                        Map.of("risk_signal", "LOW", "detected_pattern", "INSUFFICIENT_FUNDS_PATTERN")
                )
        );

        // STEP 1: Revenue Event Ingestion
        String paymentId = "pay_e2e_path_" + UUID.randomUUID().toString().substring(0, 8);
        WebhookEvent event = new WebhookEvent(
                UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                String.format("{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"%s\",\"error_code\":\"insufficient_funds\"}}}}", paymentId)
        );
        event.setMerchantId(merchantA.getId());
        event = webhookEventRepository.save(event);

        // STEP 2: Failure Diagnosis & Classification
        ClassificationResult classification = classificationService.classifyAndPersist(event, "insufficient_funds", "Low balance");
        assertNotNull(classification);
        assertEquals("INSUFFICIENT_FUNDS", classification.getFailureClass());

        // STEP 3 & 4 & 5: Campaign State, Strategy, Compliance & ActionIntent Creation
        Campaign campaign = campaignRepository.findByMerchantIdAndPaymentId(merchantA.getId(), paymentId).orElseThrow();
        assertEquals(CampaignStatus.ACTION_PENDING, campaign.getCurrentState());

        List<ActionIntent> intents = actionIntentRepository.findByCampaignId(campaign.getId());
        assertEquals(1, intents.size());
        ActionIntent intent = intents.get(0);
        assertTrue(intent.getStatus() == ActionIntentStatus.SCHEDULED || intent.getStatus() == ActionIntentStatus.READY);

        // Prepare ActionIntent for Scheduler Execution: Set to READY state with past scheduledAt
        intent.setStatus(ActionIntentStatus.READY);
        intent.setScheduledAt(Instant.now().minusSeconds(10));
        actionIntentRepository.save(intent);

        // STEP 6 & 7 & 8: Action Execution via Scheduler
        actionScheduler.pollAndExecuteDueActions();

        // STEP 9: Outcome Verification
        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.SUCCEEDED, updatedIntent.getStatus());

        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, updatedCampaign.getCurrentState());

        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        assertEquals("SUCCESS", attempts.get(0).getStatus());

        // Verify Audit Log Trace
        List<AuditLog> auditLogs = auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantA.getId());
        assertThat(auditLogs).anyMatch(log -> "AI_CLASSIFICATION_COMPLETED".equals(log.getEventType()));
        assertThat(auditLogs).anyMatch(log -> "ACTION_EXECUTED".equals(log.getEventType()) || "ACTION_EXECUTING".equals(log.getEventType()));
    }
}
