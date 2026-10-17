package com.mend.service;

import com.mend.compliance.ComplianceDecision;
import com.mend.domain.entity.*;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.repository.*;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
@DisplayName("Compliance Service Integration Tests")
public class ComplianceServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ComplianceService complianceService;

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
    private ComplianceDecisionRepository complianceDecisionRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    private UUID merchantId;
    private UUID tenantBId;
    private AuthenticatedUser merchantAdminUser;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();

        Merchant merchantA = new Merchant(merchantId, "Alpha Merchant");
        Merchant merchantB = new Merchant(tenantBId, "Beta Merchant");
        merchantRepository.save(merchantA);
        merchantRepository.save(merchantB);

        UUID userId = UUID.randomUUID();
        AuthenticatedUser.MerchantMembershipInfo membership = new AuthenticatedUser.MerchantMembershipInfo(
                merchantId, "Alpha Merchant", UUID.randomUUID(), "MERCHANT_ADMIN"
        );
        merchantAdminUser = new AuthenticatedUser(
                userId,
                "admin@alpha.com",
                "Alpha Admin",
                "ACTIVE",
                Collections.singletonList(membership)
        );
    }

    @Test
    @DisplayName("End-to-End Pipeline: Classification -> Campaign -> Strategy -> Compliance Gate (Allowed)")
    void testEndToEndComplianceAllowedPipeline() {
        // 1. Webhook Event
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-COMP-1", "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        // 2. Campaign Lifecycle to ELIGIBLE
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-COMP-1", "CUST-1", "SUB-1");
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);

        // 3. Classification Result
        ClassificationResult classification = new ClassificationResult(
                UUID.randomUUID(), event.getId(), campaign.getId(),
                "INSUFFICIENT_FUNDS", new BigDecimal("0.95"),
                "SCHEDULE_RETRY", "Soft failure", "v1.0.0"
        );
        classificationResultRepository.save(classification);

        // 4. Strategy Engine Evaluation
        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());

        // 5. Compliance Gate Evaluation
        ComplianceDecision complianceDecision = complianceService.evaluateAndPersistCompliance(merchantId, campaign.getId());

        assertNotNull(complianceDecision);
        assertTrue(complianceDecision.isAllowed());
        assertEquals(ComplianceStatus.COMPLIANCE_ALLOWED, complianceDecision.getStatus());
        assertEquals(ComplianceReason.ALLOWED, complianceDecision.getReason());
        assertEquals(RecoveryStrategy.RETRY_LATER, complianceDecision.getStrategy());
        assertEquals("v1.0", complianceDecision.getPolicyVersion());

        // 6. DB Verification
        List<ComplianceDecisionEntity> persistedDecisions = complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
        assertFalse(persistedDecisions.isEmpty());
        assertEquals(ComplianceStatus.COMPLIANCE_ALLOWED, persistedDecisions.get(0).getStatus());

        // 7. Audit Log Verification
        List<AuditLog> auditLogs = auditService.getCampaignAuditLogs(campaign.getId());
        assertTrue(auditLogs.stream().anyMatch(log -> "COMPLIANCE_ALLOWED".equals(log.getEventType())));
    }

    @Test
    @DisplayName("End-to-End Pipeline: Low Confidence Strategy -> Compliance Gate (Blocked)")
    void testEndToEndComplianceBlockedPipeline() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-COMP-2", "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-COMP-2", null, null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);

        // Low confidence classification (0.60 < 0.80)
        ClassificationResult classification = new ClassificationResult(
                UUID.randomUUID(), event.getId(), campaign.getId(),
                "INSUFFICIENT_FUNDS", new BigDecimal("0.60"),
                "SCHEDULE_RETRY", "Low confidence", "v1.0.0"
        );
        classificationResultRepository.save(classification);

        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());

        ComplianceDecision complianceDecision = complianceService.evaluateAndPersistCompliance(merchantId, campaign.getId());

        assertNotNull(complianceDecision);
        assertTrue(complianceDecision.isBlocked());
        assertEquals(ComplianceStatus.COMPLIANCE_BLOCKED, complianceDecision.getStatus());
        assertEquals(ComplianceReason.STRATEGY_NOT_SUPPORTED, complianceDecision.getReason()); // Strategy Engine evaluated MANUAL_REVIEW, which Compliance Gate blocks as non-executable

        // DB Verification
        List<ComplianceDecisionEntity> persistedDecisions = complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
        assertFalse(persistedDecisions.isEmpty());
        assertEquals(ComplianceStatus.COMPLIANCE_BLOCKED, persistedDecisions.get(0).getStatus());

        // Audit Log Verification
        List<AuditLog> auditLogs = auditService.getCampaignAuditLogs(campaign.getId());
        assertTrue(auditLogs.stream().anyMatch(log -> "COMPLIANCE_BLOCKED".equals(log.getEventType())));
    }

    @Test
    @DisplayName("Should enforce tenant isolation and block cross-tenant compliance evaluation or history access")
    void testComplianceTenantIsolation() {
        Campaign campaignA = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-COMP-TENANT", null, null);

        // Cross-tenant compliance evaluation attempt -> Blocked
        assertThrows(TenantAccessDeniedException.class, () ->
                complianceService.evaluateAndPersistCompliance(tenantBId, campaignA.getId()));

        // Cross-tenant compliance history query -> Blocked
        assertThrows(TenantAccessDeniedException.class, () ->
                complianceService.getComplianceHistory(tenantBId, campaignA.getId(), merchantAdminUser));
    }

    /**
     * Regression (cleanup finding 1): the duplicate-action check used to miss the M2 in-flight states, so a second intent
     * for the same attempt could pass compliance while the first was awaiting payment or in reconciliation.
     */
    @ParameterizedTest
    @EnumSource(value = ActionIntentStatus.class, names = {"AWAITING_PAYMENT", "NEEDS_RECONCILIATION"})
    @DisplayName("A second intent for the same attempt is blocked while the first is AWAITING_PAYMENT / NEEDS_RECONCILIATION")
    void secondIntentForSameAttemptIsBlockedWhileFirstIsInM2InFlightState(ActionIntentStatus firstIntentStatus) {
        Campaign campaign = eligibleCampaignWithFirstIntent("PAY-COMP-DUP-" + firstIntentStatus, firstIntentStatus);

        ComplianceDecision decision = complianceService.evaluateAndPersistCompliance(merchantId, campaign.getId());

        assertTrue(decision.isBlocked());
        assertEquals(ComplianceReason.DUPLICATE_ACTION, decision.getReason());
    }

    @Test
    @DisplayName("Control: an ended (FAILED) first intent for the same attempt does not block")
    void endedFirstIntentDoesNotBlock() {
        Campaign campaign = eligibleCampaignWithFirstIntent("PAY-COMP-DUP-FAILED", ActionIntentStatus.FAILED);

        ComplianceDecision decision = complianceService.evaluateAndPersistCompliance(merchantId, campaign.getId());

        assertTrue(decision.isAllowed());
        assertEquals(ComplianceReason.ALLOWED, decision.getReason());
    }

    @Test
    @DisplayName("Existing-action states = every in-flight state + PENDING, SCHEDULED, READY, SUCCEEDED")
    void existingActionStatesIncludeEveryInFlightState() {
        EnumSet<ActionIntentStatus> expected = EnumSet.of(
                ActionIntentStatus.PENDING, ActionIntentStatus.SCHEDULED, ActionIntentStatus.READY,
                ActionIntentStatus.CLAIMED, ActionIntentStatus.EXECUTING, ActionIntentStatus.NEEDS_RECONCILIATION,
                ActionIntentStatus.AWAITING_PAYMENT, ActionIntentStatus.SUCCEEDED);
        for (ActionIntentStatus status : ActionIntentStatus.values()) {
            assertEquals(expected.contains(status), ComplianceService.countsAsExistingAction(status), status.name());
            if (status.isInFlight()) {
                assertTrue(ComplianceService.countsAsExistingAction(status), status + " is in flight");
            }
        }
        assertFalse(ComplianceService.countsAsExistingAction(null));
    }

    /**
     * An ELIGIBLE campaign whose strategy is allowed (same context as the allowed pipeline above), on attempt 1, with a
     * first intent for attempt 1 in {@code firstIntentStatus}. Execution sets attempt_count to the intent's attempt number.
     */
    private Campaign eligibleCampaignWithFirstIntent(String paymentId, ActionIntentStatus firstIntentStatus) {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, "CUST-DUP", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);
        classificationResultRepository.save(new ClassificationResult(
                UUID.randomUUID(), event.getId(), campaign.getId(),
                "INSUFFICIENT_FUNDS", new BigDecimal("0.95"),
                "SCHEDULE_RETRY", "Soft failure", "v1.0.0"));
        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());

        campaign.setAttemptCount(1);
        campaign = campaignRepository.saveAndFlush(campaign);

        ActionIntent first = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1,
                ActionType.REQUEST_CUSTOMER_ACTION.name(), RecoveryStrategy.RETRY_LATER.name(), null,
                firstIntentStatus, "intent:" + UUID.randomUUID(), Instant.now());
        first.setInvoiceKey(campaign.getInvoiceKey());
        actionIntentRepository.saveAndFlush(first);
        return campaign;
    }
}
