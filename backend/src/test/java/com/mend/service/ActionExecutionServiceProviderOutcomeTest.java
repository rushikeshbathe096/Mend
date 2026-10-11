package com.mend.service;

import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.mock.MockPaymentProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** One test per row of the M1 design-doc "Executor mapping" table (mock mode; razorpay-mode rows are in Phase20). */
@DisplayName("ActionExecutionService provider outcome mapping (M1)")
class ActionExecutionServiceProviderOutcomeTest extends AbstractIntegrationTest {

    @Autowired private ActionExecutionService actionExecutionService;
    @Autowired private ActionIntentRepository actionIntentRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignAttemptRepository campaignAttemptRepository;
    @Autowired private ComplianceDecisionRepository complianceDecisionRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private MockPaymentProvider mockPaymentProvider;

    private UUID merchantId;
    private Campaign campaign;
    private ComplianceDecisionEntity compliance;

    @BeforeEach
    void setUp() {
        mockPaymentProvider.reset();
        merchantId = UUID.randomUUID();
        merchantRepository.save(new Merchant(merchantId, "Outcome Mapping Merchant"));

        campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_outcome_" + UUID.randomUUID().toString().substring(0, 8));
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaignRepository.save(campaign);

        compliance = complianceDecisionRepository.save(new ComplianceDecisionEntity(UUID.randomUUID(), campaign.getId(), merchantId,
                null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED, ComplianceReason.ALLOWED,
                "Compliant action", "v1.0.0"));
    }

    @AfterEach
    void tearDown() {
        campaignRepository.deleteById(campaign.getId());
        merchantRepository.deleteById(merchantId);
    }

    private void storeOriginalPayment(long amountMinor) {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), campaign.getPaymentId(), "payment.failed", "RAZORPAY",
                "{\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_x\",\"amount\":" + amountMinor + ",\"currency\":\"INR\"}}}}");
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhookEventRepository.save(event);
    }

    private ActionIntent claimed(String actionType) {
        ActionIntent intent = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, actionType,
                RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), compliance.getId(), ActionIntentStatus.CLAIMED,
                "intent:" + campaign.getId() + ":attempt_1:" + actionType, Instant.now());
        intent.setClaimedAt(Instant.now());
        intent.setWorkerId("worker-outcome");
        // M2: a CLAIMED intent always carries a claim token (fixture correction; the dispatch fence requires it), see ADR-002
        intent.setClaimToken(UUID.randomUUID().toString());
        return actionIntentRepository.save(intent);
    }

    private PaymentExecutionResult execute(ActionIntent intent) {
        // M2 behaviour change: the executor requires the caller's claim token (fail closed), see ADR-002
        return actionExecutionService.executeActionIntent(intent.getId(), "worker-outcome", intent.getClaimToken());
    }

    private ActionIntent reload(ActionIntent intent) {
        return actionIntentRepository.findById(intent.getId()).orElseThrow();
    }

    private CampaignStatus campaignState() {
        return campaignRepository.findById(campaign.getId()).orElseThrow().getCurrentState();
    }

    private CampaignAttempt onlyAttempt() {
        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        return attempts.get(0);
    }

    private List<String> auditTypes() {
        return auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(campaign.getId()).stream().map(AuditLog::getEventType).toList();
    }

    @Test
    void linkIssuedLeavesIntentExecutingAndCampaignNotRecovered() {
        storeOriginalPayment(49900);
        ActionIntent intent = claimed(ActionType.REQUEST_CUSTOMER_ACTION.name());

        PaymentExecutionResult report = execute(intent);

        String expectedLink = "plink_mock_" + ProviderAttemptKey.forIntent(intent.getId()).value().replace("mnd_", "");
        assertEquals(PaymentExecutionStatus.PENDING, report.getStatus());
        // M2 behaviour change: a live payment link is AWAITING_PAYMENT (was EXECUTING), see ADR-002
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, reload(intent).getStatus());
        assertEquals(expectedLink, reload(intent).getResponseReference());
        assertEquals(CampaignStatus.EXECUTING, campaignState());
        assertEquals("ISSUED", onlyAttempt().getStatus());
        assertEquals(expectedLink, onlyAttempt().getExternalReference());
        assertTrue(auditTypes().contains("PROVIDER_REQUEST_ACCEPTED"));
        assertEquals(1, mockPaymentProvider.getInvocationCount());
    }

    @Test
    void rejectedFailsIntentAndExhaustsCampaign() {
        storeOriginalPayment(49900);
        mockPaymentProvider.setSimulatedStatus(PaymentExecutionStatus.FAILURE);
        mockPaymentProvider.setSimulatedFailureReason("Card expired");
        ActionIntent intent = claimed(ActionType.REQUEST_CUSTOMER_ACTION.name());

        PaymentExecutionResult report = execute(intent);

        assertEquals(PaymentExecutionStatus.FAILURE, report.getStatus());
        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertNotNull(reload(intent).getCompletedAt());
        assertNull(reload(intent).getResponseReference());
        assertEquals(CampaignStatus.EXHAUSTED, campaignState());
        assertEquals("FAILED", onlyAttempt().getStatus());
        assertTrue(auditTypes().contains("PROVIDER_REQUEST_REJECTED"));
    }

    @Test
    void unresolvableAmountIsLocalRejectionWithoutProviderCall() {
        ActionIntent intent = claimed(ActionType.REQUEST_CUSTOMER_ACTION.name());

        PaymentExecutionResult report = execute(intent);

        assertEquals("AMOUNT_UNRESOLVED", report.getResponseCode());
        // M2 behaviour change: AMOUNT_UNRESOLVED is MEND-side, so the intent is deferred (READY, later), not FAILED, see ADR-002
        assertEquals(ActionIntentStatus.READY, reload(intent).getStatus());
        assertNotEquals(CampaignStatus.EXHAUSTED, campaignState());
        assertTrue(auditTypes().contains("EXECUTION_DEFERRED"));
        assertEquals(0, mockPaymentProvider.getInvocationCount());
    }

    @Test
    void notSentFailsIntentWithNotSentAudit() {
        storeOriginalPayment(49900);
        mockPaymentProvider.setSimulatedNotSent(true);
        ActionIntent intent = claimed(ActionType.REQUEST_CUSTOMER_ACTION.name());

        PaymentExecutionResult report = execute(intent);

        assertEquals(PaymentExecutionStatus.FAILURE, report.getStatus());
        // M2 behaviour change: NotSent is MEND-side and nothing reached the provider: deferred (READY, campaign
        // ACTION_PENDING), never EXHAUSTED, see ADR-002 decision 10
        assertEquals(ActionIntentStatus.READY, reload(intent).getStatus());
        assertEquals(CampaignStatus.ACTION_PENDING, campaignState());
        assertTrue(auditTypes().contains("PROVIDER_REQUEST_NOT_SENT"));
        assertTrue(auditTypes().contains("EXECUTION_DEFERRED"));
    }

    @Test
    void unknownLeavesIntentAndCampaignExecutingAndIsNotRecycledAsExpiredClaim() {
        storeOriginalPayment(49900);
        mockPaymentProvider.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = claimed(ActionType.REQUEST_CUSTOMER_ACTION.name());

        PaymentExecutionResult report = execute(intent);

        assertEquals(PaymentExecutionStatus.PENDING, report.getStatus());
        // M2 behaviour change: an unknown outcome is NEEDS_RECONCILIATION (was EXECUTING), see ADR-002
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, reload(intent).getStatus());
        assertNull(reload(intent).getResponseReference());
        assertNull(reload(intent).getCompletedAt());
        assertEquals(CampaignStatus.EXECUTING, campaignState());
        assertEquals("UNKNOWN", onlyAttempt().getStatus());
        assertTrue(auditTypes().contains("PROVIDER_OUTCOME_UNKNOWN"));
        // releaseExpiredClaims only selects CLAIMED: an unknown outcome is never silently returned to READY.
        assertTrue(actionIntentRepository.findExpiredClaims(Instant.now().plusSeconds(3600)).stream()
                .noneMatch(expired -> expired.getId().equals(intent.getId())));
    }

    @Test
    void providerExceptionIsUnknownNotFailed() {
        storeOriginalPayment(49900);
        mockPaymentProvider.setSimulatedException(new RuntimeException("socket closed"));
        ActionIntent intent = claimed(ActionType.REQUEST_CUSTOMER_ACTION.name());

        PaymentExecutionResult report = execute(intent);

        assertEquals(PaymentExecutionStatus.PENDING, report.getStatus());
        // M2 behaviour change: an unknown outcome is NEEDS_RECONCILIATION (was EXECUTING), see ADR-002
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, reload(intent).getStatus());
        assertTrue(auditTypes().contains("PROVIDER_OUTCOME_UNKNOWN"));
    }

    @Test
    void unsupportedActionTypeIsNotSupportedWithoutProviderCall() {
        ActionIntent intent = claimed("BOGUS_ACTION");

        PaymentExecutionResult report = execute(intent);

        assertEquals("PROVIDER_OPERATION_NOT_SUPPORTED", report.getResponseCode());
        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.EXHAUSTED, campaignState());
        assertEquals(0, mockPaymentProvider.getInvocationCount());
        assertTrue(auditTypes().contains("PROVIDER_REQUEST_REJECTED"));
    }

    @Test
    void mockSyntheticCaptureRecoversAndIsLabelledSynthetic() {
        ActionIntent intent = claimed(ActionType.RETRY_PAYMENT.name());

        PaymentExecutionResult report = execute(intent);

        assertEquals(PaymentExecutionStatus.SUCCESS, report.getStatus());
        assertTrue(report.getExternalReference().startsWith("pay_mock_"));
        assertEquals(ActionIntentStatus.SUCCEEDED, reload(intent).getStatus());
        assertEquals(CampaignStatus.RECOVERED, campaignState());
        assertTrue(auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(campaign.getId()).stream()
                .anyMatch(a -> "ACTION_SUCCEEDED".equals(a.getEventType()) && a.getReason().contains("SYNTHETIC")));
    }
}
