package com.mend.service;

import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.InvalidCampaignStateException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.scheduler.ActionScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
@DisplayName("Action Intent Service & Scheduler Integration Tests")
public class ActionIntentServiceTest extends AbstractIntegrationTest {

    @Autowired
    private com.mend.domain.repository.ReviewQueueRepository reviewQueueRepositoryU9;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplateU9;

    @Autowired
    private com.mend.domain.repository.MerchantConfigRepository merchantConfigRepositoryM3;

    @Autowired
    private ActionIntentService actionIntentService;

    @Autowired
    private ActionScheduler actionScheduler;

    @Autowired
    private ActionIntentClaimService actionIntentClaimService;

    @Autowired
    private RecoveryStrategyService recoveryStrategyService;

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private AuditService auditService;

    private UUID merchantId;
    private UUID tenantBId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();

        Merchant merchantA = new Merchant(merchantId, "Alpha Corp");
        Merchant merchantB = new Merchant(tenantBId, "Beta Corp");
        merchantRepository.save(merchantA);
        merchantRepository.save(merchantB);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // Bug sweep item 6: delete this test's two merchants (their campaigns and intents cascade), not every intent.
        com.mend.testsupport.TestRows.deleteMerchant(jdbcTemplateU9, merchantId);
        com.mend.testsupport.TestRows.deleteMerchant(jdbcTemplateU9, tenantBId);
    }

    @Test
    @DisplayName("Task 20 Integration Flow: Webhook -> Classification -> Campaign -> Strategy -> Compliance -> Action Intent -> Scheduler")
    void testEndToEndPipelineToScheduler() {
        String fixtureSuffix = UUID.randomUUID().toString();
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-INTENT-1-" + fixtureSuffix, "payment.failed");
        event.setMerchantId(merchantId);
        webhookEventRepository.save(event);

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(
                merchantId, "PAY-INTENT-1-" + fixtureSuffix, "CUST-1", "SUB-1");
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);

        final UUID targetCampaignId = campaign.getId();

        ClassificationResult classification = com.mend.testsupport.TestClassifications.deterministic(new ClassificationResult(
                UUID.randomUUID(), event.getId(), targetCampaignId,
                "INSUFFICIENT_FUNDS", new BigDecimal("0.95"),
                "SCHEDULE_RETRY", "Soft failure", "v1.0.0"
        ));
        classificationResultRepository.save(classification);

        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, targetCampaignId);

        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, targetCampaignId);

        assertNotNull(intent);
        assertEquals(merchantId, intent.getMerchantId());
        assertEquals(targetCampaignId, intent.getCampaignId());
        assertEquals(ActionType.RETRY_PAYMENT.name(), intent.getActionType());
        assertEquals("RETRY_LATER", intent.getSourceStrategy());
        assertNotNull(intent.getComplianceDecisionId());
        assertNotNull(intent.getIdempotencyKey());
        assertEquals(ActionIntentStatus.SCHEDULED, intent.getStatus());

        campaignLifecycleService.transitionState(
                merchantId, targetCampaignId, CampaignStatus.ACTION_PENDING,
                "Action intent ready for scheduler", "SYSTEM", null);
        intent.setScheduledAt(Instant.now().minusSeconds(10));
        actionIntentRepository.save(intent);

        // The scheduler is a separate worker. Commit the fixture transaction so
        // its independent claim transaction observes production-like data.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        // Shared database: other merchants' due rows (e.g. other classes' deferred or SAFE_RETRY'd intents) must not be
        // older in the claim batch than this fixture (same parking pattern as the M2 tests).
        jdbcTemplateU9.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' "
                + "WHERE status IN ('READY','SCHEDULED','PENDING') AND merchant_id <> ?", merchantId);

        List<ActionIntent> claimed = actionIntentClaimService.claimDueIntents("worker-alpha", 10);
        assertFalse(claimed.isEmpty());
        assertEquals(intent.getId(), claimed.get(0).getId());
        assertEquals(ActionIntentStatus.CLAIMED, claimed.get(0).getStatus());
        assertEquals("worker-alpha", claimed.get(0).getWorkerId());

        List<AuditLog> auditLogs = auditService.getCampaignAuditLogs(targetCampaignId);
        assertTrue(auditLogs.stream().anyMatch(log -> "ACTION_INTENT_CREATED".equals(log.getEventType())));
    }

    @Test
    @DisplayName("Blocked compliance prohibits Action Intent creation")
    void testBlockedComplianceProhibitsActionIntent() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-INTENT-2", "payment.failed");
        event.setMerchantId(merchantId);
        webhookEventRepository.save(event);

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-INTENT-2", null, null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);

        final UUID targetCampaignId = campaign.getId();

        // M3 behaviour change (ADR-003 §4): was a low-confidence (0.50) row; MANUAL_REVIEW now comes from the merchant's
        // enabled actions (INSUFFICIENT_FUNDS computes RETRY_LATER, which this merchant has not enabled).
        com.mend.domain.entity.MerchantConfig config = new com.mend.domain.entity.MerchantConfig(UUID.randomUUID(), merchantId);
        config.setEnabledRecoveryActions("CUSTOMER_ACTION_REQUIRED");
        merchantConfigRepositoryM3.save(config);
        ClassificationResult classification = com.mend.testsupport.TestClassifications.deterministic(new ClassificationResult(
                UUID.randomUUID(), event.getId(), targetCampaignId,
                "INSUFFICIENT_FUNDS", null,
                null, "Razorpay error_reason 'insufficient_funds'", null
        ));
        classificationResultRepository.save(classification);

        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, targetCampaignId);

        // M2 behaviour change: a MANUAL_REVIEW strategy creates a review item and no intent (returns null) instead of
        // throwing, see ADR-002 decision 10
        assertNull(actionIntentService.createActionIntentFromCompliance(merchantId, targetCampaignId));
        assertTrue(reviewQueueRepositoryU9.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(targetCampaignId,
                com.mend.domain.enums.ReviewQueueStatus.PENDING).orElseThrow().getReason().startsWith("MANUAL_REVIEW_REQUIRED"));

        List<ActionIntent> intents = actionIntentRepository.findByCampaignId(targetCampaignId);
        assertTrue(intents.isEmpty());
    }

    @Test
    @DisplayName("Duplicate event returns existing Action Intent (Idempotency)")
    void testIdempotencyReturnsExistingIntent() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-INTENT-3", "payment.failed");
        event.setMerchantId(merchantId);
        webhookEventRepository.save(event);

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-INTENT-3", null, null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);

        final UUID targetCampaignId = campaign.getId();

        ClassificationResult classification = com.mend.testsupport.TestClassifications.deterministic(new ClassificationResult(
                UUID.randomUUID(), event.getId(), targetCampaignId,
                "INSUFFICIENT_FUNDS", new BigDecimal("0.95"),
                "SCHEDULE_RETRY", "Soft failure", "v1.0.0"
        ));
        classificationResultRepository.save(classification);
        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, targetCampaignId);

        ActionIntent intent1 = actionIntentService.createActionIntentFromCompliance(merchantId, targetCampaignId);
        ActionIntent intent2 = actionIntentService.createActionIntentFromCompliance(merchantId, targetCampaignId);

        assertNotNull(intent1);
        assertNotNull(intent2);
        assertEquals(intent1.getId(), intent2.getId());
        assertEquals(1, actionIntentRepository.findByCampaignId(targetCampaignId).size());
    }

    @Test
    @DisplayName("Non-eligible campaign state throws InvalidCampaignStateException")
    void testNonEligibleCampaignStateRejected() {
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-INTENT-4", null, null);
        final UUID targetCampaignId = campaign.getId();

        assertThrows(InvalidCampaignStateException.class, () ->
                actionIntentService.createActionIntentFromCompliance(merchantId, targetCampaignId));
    }

    @Test
    @DisplayName("Cross-tenant Action Intent creation is blocked")
    void testCrossTenantAttemptBlocked() {
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-INTENT-5", null, null);
        final UUID targetCampaignId = campaign.getId();

        assertThrows(TenantAccessDeniedException.class, () ->
                actionIntentService.createActionIntentFromCompliance(tenantBId, targetCampaignId));
    }

    @Test
    @DisplayName("Cancellation of pending intents for campaign")
    void testCancellationOfPendingIntents() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-INTENT-6", "payment.failed");
        event.setMerchantId(merchantId);
        webhookEventRepository.save(event);

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-INTENT-6", null, null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);

        final UUID targetCampaignId = campaign.getId();

        ClassificationResult classification = com.mend.testsupport.TestClassifications.deterministic(new ClassificationResult(
                UUID.randomUUID(), event.getId(), targetCampaignId,
                "INSUFFICIENT_FUNDS", new BigDecimal("0.95"),
                "SCHEDULE_RETRY", "Soft failure", "v1.0.0"
        ));
        classificationResultRepository.save(classification);
        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, targetCampaignId);

        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, targetCampaignId);
        assertNotNull(intent);

        actionIntentService.cancelPendingIntentsForCampaign(merchantId, targetCampaignId, "Payment recovered externally");

        ActionIntent updated = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.CANCELLED, updated.getStatus());
        assertNotNull(updated.getCompletedAt());
    }
}
