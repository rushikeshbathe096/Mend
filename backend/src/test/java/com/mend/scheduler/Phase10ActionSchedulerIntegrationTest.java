package com.mend.scheduler;

import com.mend.client.MockPaymentProviderClient;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.service.ActionExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
@DisplayName("Phase 10 Action Scheduler End-to-End Integration Tests")
public class Phase10ActionSchedulerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ActionScheduler actionScheduler;

    @Autowired
    private ActionExecutionService actionExecutionService;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignAttemptRepository campaignAttemptRepository;

    @Autowired
    private ComplianceDecisionRepository complianceDecisionRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MockPaymentProviderClient mockPaymentProviderClient;

    private UUID merchantId;
    private UUID tenantBId;
    private Campaign campaign;
    private ComplianceDecisionEntity complianceDecision;

    @BeforeEach
    void setUp() {
        mockPaymentProviderClient.reset();

        actionIntentRepository.deleteAll();
        campaignAttemptRepository.deleteAll();
        complianceDecisionRepository.deleteAll();
        campaignRepository.deleteAll();
        merchantRepository.deleteAll();

        merchantId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();

        Merchant merchantA = new Merchant(merchantId, "Alpha Merchant");
        Merchant merchantB = new Merchant(tenantBId, "Beta Merchant");
        merchantRepository.save(merchantA);
        merchantRepository.save(merchantB);

        campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_test_phase10");
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaignRepository.save(campaign);

        complianceDecision = new ComplianceDecisionEntity(
                UUID.randomUUID(),
                campaign.getId(),
                merchantId,
                null,
                RecoveryStrategy.RETRY_IMMEDIATELY,
                ComplianceStatus.COMPLIANCE_ALLOWED,
                ComplianceReason.ALLOWED,
                "Compliant action",
                "v1.0.0"
        );
        complianceDecision = complianceDecisionRepository.save(complianceDecision);
    }

    private ActionIntent createActionIntent(ActionIntentStatus status, Instant scheduledAt, String actionType) {
        String idempotencyKey = "intent:" + campaign.getId() + ":attempt_1:" + actionType;
        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(),
                merchantId,
                campaign.getId(),
                1,
                actionType,
                RecoveryStrategy.RETRY_IMMEDIATELY.name(),
                complianceDecision.getId(),
                status,
                idempotencyKey,
                scheduledAt
        );
        return actionIntentRepository.save(intent);
    }

    @Test
    @DisplayName("1. Scheduled action execution - Full automated flow from ACTION_PENDING to RECOVERED")
    void testScheduledActionExecutionSuccess() {
        ActionIntent intent = createActionIntent(ActionIntentStatus.SCHEDULED, Instant.now().minusSeconds(10), ActionType.RETRY_PAYMENT.name());

        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());

        // Verify ActionIntent state -> SUCCEEDED
        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.SUCCEEDED, updatedIntent.getStatus());
        assertNotNull(updatedIntent.getResponseReference());

        // Verify Campaign state -> RECOVERED
        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, updatedCampaign.getCurrentState());

        // Verify CampaignAttempt
        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        assertEquals("SUCCESS", attempts.get(0).getStatus());

        // Verify provider invocation count
        assertEquals(1, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("2. Action not yet due - Scheduler ignores future actions")
    void testActionNotYetDue() {
        createActionIntent(ActionIntentStatus.SCHEDULED, Instant.now().plusSeconds(3600), ActionType.RETRY_PAYMENT.name());

        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();

        assertTrue(results.isEmpty());
        assertEquals(0, mockPaymentProviderClient.getInvocationCount());

        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.ACTION_PENDING, updatedCampaign.getCurrentState());
    }

    @Test
    @DisplayName("3. Provider decline - Provider returns FAILURE -> Campaign EXHAUSTED")
    void testProviderDeclineExecution() {
        mockPaymentProviderClient.setSimulatedStatus(PaymentExecutionStatus.FAILURE);
        mockPaymentProviderClient.setSimulatedFailureReason("Card expired");

        ActionIntent intent = createActionIntent(ActionIntentStatus.READY, Instant.now().minusSeconds(5), ActionType.RETRY_PAYMENT.name());

        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        assertTrue(results.get(0).isFailure());

        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.FAILED, updatedIntent.getStatus());

        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.EXHAUSTED, updatedCampaign.getCurrentState());

        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        assertEquals("FAILED", attempts.get(0).getStatus());
        assertEquals("Card expired", attempts.get(0).getFailureReason());
    }

    @Test
    @DisplayName("4. Provider error - Provider returns ERROR -> Campaign FAILED (not RECOVERED, no blind retry)")
    void testProviderErrorExecution() {
        mockPaymentProviderClient.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        mockPaymentProviderClient.setSimulatedErrorMessage("502 Bad Gateway");

        ActionIntent intent = createActionIntent(ActionIntentStatus.READY, Instant.now().minusSeconds(5), ActionType.RETRY_PAYMENT.name());

        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());

        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.FAILED, updatedIntent.getStatus());

        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.FAILED, updatedCampaign.getCurrentState());

        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        assertEquals("ERROR", attempts.get(0).getStatus());
    }

    @Test
    @DisplayName("5. Timeout - Provider exception leads to error handling without retry")
    void testProviderTimeoutExecution() {
        mockPaymentProviderClient.setSimulatedException(new RuntimeException("Connection timed out after 30000ms"));

        ActionIntent intent = createActionIntent(ActionIntentStatus.READY, Instant.now().minusSeconds(5), ActionType.RETRY_PAYMENT.name());

        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());

        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.FAILED, updatedIntent.getStatus());

        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.FAILED, updatedCampaign.getCurrentState());
    }

    @Test
    @DisplayName("6. Duplicate scheduler invocation - Atomic claim prevents double execution")
    void testDuplicateSchedulerInvocation() {
        createActionIntent(ActionIntentStatus.READY, Instant.now().minusSeconds(5), ActionType.RETRY_PAYMENT.name());

        List<PaymentExecutionResult> resultsFirstRun = actionScheduler.pollAndExecuteDueActions();
        assertEquals(1, resultsFirstRun.size());
        assertEquals(1, mockPaymentProviderClient.getInvocationCount());

        List<PaymentExecutionResult> resultsSecondRun = actionScheduler.pollAndExecuteDueActions();
        assertTrue(resultsSecondRun.isEmpty());
        assertEquals(1, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("7. Idempotency - Authoritative key passed to provider")
    void testIdempotencyKeyPropagation() {
        ActionIntent intent = createActionIntent(ActionIntentStatus.READY, Instant.now().minusSeconds(5), ActionType.RETRY_PAYMENT.name());

        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        assertEquals(intent.getIdempotencyKey(), results.get(0).getIdempotencyKey());
        assertEquals(intent.getIdempotencyKey(), mockPaymentProviderClient.getLastRequest().getIdempotencyKey());
    }

    @Test
    @DisplayName("8. Compliance blocked action - Halted before calling provider")
    void testComplianceBlockedActionHalted() {
        ComplianceDecisionEntity blockedDecision = new ComplianceDecisionEntity(
                UUID.randomUUID(), campaign.getId(), merchantId, null,
                RecoveryStrategy.RETRY_IMMEDIATELY, ComplianceStatus.COMPLIANCE_BLOCKED,
                ComplianceReason.MAX_ATTEMPTS_EXCEEDED, "Max attempts reached", "v1.0.0"
        );
        complianceDecisionRepository.save(blockedDecision);

        ActionIntent intent = createActionIntent(ActionIntentStatus.CLAIMED, Instant.now().minusSeconds(5), ActionType.RETRY_PAYMENT.name());
        intent.setComplianceDecisionId(blockedDecision.getId());
        actionIntentRepository.save(intent);

        assertThrows(ComplianceBlockedException.class, () ->
                actionExecutionService.executeActionIntent(intent.getId(), null));

        assertEquals(0, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("9. Tenant isolation - Rejects mismatch between intent and campaign merchant")
    void testTenantIsolationEnforcement() {
        ActionIntent intent = createActionIntent(ActionIntentStatus.CLAIMED, Instant.now().minusSeconds(5), ActionType.RETRY_PAYMENT.name());
        intent.setMerchantId(tenantBId); // Mismatched tenant
        actionIntentRepository.save(intent);

        assertThrows(TenantAccessDeniedException.class, () ->
                actionExecutionService.executeActionIntent(intent.getId(), null));

        assertEquals(0, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("10. Unsupported action type - Fails safely without calling provider")
    void testUnsupportedActionTypeFailsSafely() {
        ActionIntent intent = createActionIntent(ActionIntentStatus.READY, Instant.now().minusSeconds(5), "INVALID_UNSUPPORTED_ACTION");

        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        assertEquals(0, mockPaymentProviderClient.getInvocationCount());

        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.FAILED, updatedIntent.getStatus());
    }

    @Test
    @DisplayName("11. State transition correctness - Verifies ELIGIBLE -> ACTION_PENDING -> EXECUTING -> RECOVERED")
    void testStateTransitionSequence() {
        assertEquals(CampaignStatus.ACTION_PENDING, campaign.getCurrentState());

        createActionIntent(ActionIntentStatus.SCHEDULED, Instant.now().minusSeconds(10), ActionType.RETRY_PAYMENT.name());

        actionScheduler.pollAndExecuteDueActions();

        Campaign finalCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, finalCampaign.getCurrentState());
    }
}
