package com.mend.service;

import com.mend.client.MockPaymentProviderClient;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.integration.AbstractIntegrationTest;
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
@DisplayName("Action Execution Service Integration Tests")
public class ActionExecutionServiceTest extends AbstractIntegrationTest {

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

        merchantId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();

        Merchant merchantA = new Merchant(merchantId, "Alpha Merchant");
        Merchant merchantB = new Merchant(tenantBId, "Beta Merchant");
        merchantRepository.save(merchantA);
        merchantRepository.save(merchantB);

        campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_test_100");
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

    private ActionIntent createClaimedIntent(String idempotencyKey) {
        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(),
                merchantId,
                campaign.getId(),
                1,
                ActionType.RETRY_PAYMENT.name(),
                RecoveryStrategy.RETRY_IMMEDIATELY.name(),
                complianceDecision.getId(),
                ActionIntentStatus.CLAIMED,
                idempotencyKey,
                Instant.now()
        );
        intent.setClaimedAt(Instant.now());
        intent.setWorkerId("worker-test-1");
        intent.setClaimToken("token-test-1");
        return actionIntentRepository.save(intent);
    }

    @Test
    @DisplayName("Should successfully execute claimed intent and transition campaign to RECOVERED")
    void testSuccessfulActionExecution() {
        String key = "intent:camp-1:attempt_1:RETRY_PAYMENT";
        ActionIntent intent = createClaimedIntent(key);

        PaymentExecutionResult result = actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1");

        // Assert 1: Result is SUCCESS
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(key, result.getIdempotencyKey());
        assertNotNull(result.getExternalReference());

        // Assert 2: ActionIntent is SUCCEEDED
        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.SUCCEEDED, updatedIntent.getStatus());
        assertEquals(result.getExternalReference(), updatedIntent.getResponseReference());
        assertNotNull(updatedIntent.getCompletedAt());

        // Assert 3: Campaign is RECOVERED
        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, updatedCampaign.getCurrentState());
        assertEquals(1, updatedCampaign.getAttemptCount());

        // Assert 4: CampaignAttempt created in DB
        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        CampaignAttempt attempt = attempts.get(0);
        assertEquals("SUCCESS", attempt.getStatus());
        assertEquals(result.getExternalReference(), attempt.getExternalReference());
        assertEquals(1, attempt.getAttemptNumber());

        // Assert 5: Mock provider invoked exactly once
        assertEquals(1, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("Should handle provider FAILURE cleanly and transition campaign to FAILED/EXHAUSTED without RECOVERED state")
    void testProviderFailureExecution() {
        mockPaymentProviderClient.setSimulatedStatus(PaymentExecutionStatus.FAILURE);
        mockPaymentProviderClient.setSimulatedFailureReason("Insufficent funds in customer account");

        String key = "intent:camp-2:attempt_1:RETRY_PAYMENT";
        ActionIntent intent = createClaimedIntent(key);

        PaymentExecutionResult result = actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1");

        // Assert 1: Result is FAILURE
        assertNotNull(result);
        assertTrue(result.isFailure());

        // Assert 2: ActionIntent is FAILED
        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.FAILED, updatedIntent.getStatus());

        // Assert 3: Campaign is EXHAUSTED (not RECOVERED)
        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertNotEquals(CampaignStatus.RECOVERED, updatedCampaign.getCurrentState());
        assertEquals(CampaignStatus.EXHAUSTED, updatedCampaign.getCurrentState());

        // Assert 4: CampaignAttempt created in DB with FAILED status
        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        assertEquals("FAILED", attempts.get(0).getStatus());
        assertEquals("Insufficent funds in customer account", attempts.get(0).getFailureReason());
    }

    @Test
    @DisplayName("Should handle provider ERROR/Timeout without falsely marking campaign as RECOVERED")
    void testProviderErrorExecution() {
        mockPaymentProviderClient.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        mockPaymentProviderClient.setSimulatedErrorMessage("Provider connection timeout (HTTP 504)");

        String key = "intent:camp-3:attempt_1:RETRY_PAYMENT";
        ActionIntent intent = createClaimedIntent(key);

        PaymentExecutionResult result = actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1");

        // Assert 1: Result is ERROR
        assertNotNull(result);
        assertTrue(result.isError());

        // Assert 2: ActionIntent is FAILED
        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.FAILED, updatedIntent.getStatus());

        // Assert 3: Campaign is FAILED (not RECOVERED)
        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertNotEquals(CampaignStatus.RECOVERED, updatedCampaign.getCurrentState());
        assertEquals(CampaignStatus.FAILED, updatedCampaign.getCurrentState());

        // Assert 4: CampaignAttempt created with ERROR status
        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(campaign.getId());
        assertEquals(1, attempts.size());
        assertEquals("ERROR", attempts.get(0).getStatus());
    }

    @Test
    @DisplayName("Should block execution and avoid calling provider when compliance is BLOCKED")
    void testComplianceBlockedExecution() {
        ComplianceDecisionEntity blockedCompliance = new ComplianceDecisionEntity(
                UUID.randomUUID(), campaign.getId(), merchantId, null,
                RecoveryStrategy.RETRY_IMMEDIATELY, ComplianceStatus.COMPLIANCE_BLOCKED,
                ComplianceReason.MAX_ATTEMPTS_EXCEEDED, "Max retry attempts reached", "v1.0.0"
        );
        blockedCompliance = complianceDecisionRepository.save(blockedCompliance);

        ActionIntent intent = createClaimedIntent("intent:camp-4:attempt_1:RETRY_PAYMENT");
        intent.setComplianceDecisionId(blockedCompliance.getId());
        actionIntentRepository.save(intent);

        assertThrows(ComplianceBlockedException.class, () ->
                actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1"));

        assertEquals(0, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("Should halt execution when campaign is in non-executable CANCELLED state")
    void testCancelledCampaignExecution() {
        campaign.setCurrentState(CampaignStatus.CANCELLED);
        campaignRepository.save(campaign);

        ActionIntent intent = createClaimedIntent("intent:camp-5:attempt_1:RETRY_PAYMENT");

        PaymentExecutionResult result = actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1");

        assertTrue(result.isFailure());
        assertEquals(0, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("Should mark ActionIntent as EXHAUSTED and halt execution when intent is past expiresAt")
    void testExpiredIntentExecution() {
        ActionIntent intent = createClaimedIntent("intent:camp-6:attempt_1:RETRY_PAYMENT");
        intent.setExpiresAt(Instant.now().minusSeconds(300));
        actionIntentRepository.save(intent);

        PaymentExecutionResult result = actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1");

        assertTrue(result.isFailure());
        ActionIntent updatedIntent = actionIntentRepository.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.EXPIRED, updatedIntent.getStatus());
        assertEquals(0, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("Should reject execution when tenant mismatch occurs between ActionIntent and Campaign")
    void testTenantMismatchExecution() {
        ActionIntent intent = createClaimedIntent("intent:camp-7:attempt_1:RETRY_PAYMENT");
        intent.setMerchantId(tenantBId); // Mismatched tenant
        actionIntentRepository.save(intent);

        assertThrows(TenantAccessDeniedException.class, () ->
                actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1"));

        assertEquals(0, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("Should prevent duplicate execution when ActionIntent has already reached terminal status")
    void testDuplicateExecutionPrevention() {
        ActionIntent intent = createClaimedIntent("intent:camp-8:attempt_1:RETRY_PAYMENT");
        intent.setStatus(ActionIntentStatus.SUCCEEDED);
        actionIntentRepository.save(intent);

        PaymentExecutionResult result = actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1");

        assertEquals(0, mockPaymentProviderClient.getInvocationCount());
        assertTrue(result.isFailure());
    }

    @Test
    @DisplayName("Should fail closed and avoid provider call when compliance decision is missing")
    void testMissingComplianceDecisionExecution() {
        ActionIntent intent = createClaimedIntent("intent:camp-9:attempt_1:RETRY_PAYMENT");
        intent.setComplianceDecisionId(null);
        actionIntentRepository.save(intent);

        complianceDecisionRepository.deleteAll();

        assertThrows(ComplianceBlockedException.class, () ->
                actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1"));

        assertEquals(0, mockPaymentProviderClient.getInvocationCount());
    }

    @Test
    @DisplayName("Should propagate idempotencyKey unchanged to PaymentProviderClient and back in result")
    void testIdempotencyKeyPropagation() {
        String expectedKey = "intent:custom_camp_key_123:attempt_1:RETRY_PAYMENT";
        ActionIntent intent = createClaimedIntent(expectedKey);

        PaymentExecutionResult result = actionExecutionService.executeActionIntent(intent.getId(), "worker-test-1");

        assertNotNull(result);
        assertEquals(expectedKey, result.getIdempotencyKey());
        assertEquals(1, mockPaymentProviderClient.getInvocationCount());
        assertEquals(expectedKey, mockPaymentProviderClient.getLastRequest().getIdempotencyKey());
    }
}
