package com.mend.scheduler;

import com.mend.provider.MerchantRef;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RequestResult;
import com.mend.provider.mock.SyntheticDebitSimulator;
import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.CampaignAttempt;
import com.mend.domain.entity.ComplianceDecisionEntity;
import com.mend.domain.entity.Merchant;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignAttemptRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.ComplianceDecisionRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.service.ActionIntentClaimService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(ActionSchedulerTransactionIntegrationTest.ProviderConfiguration.class)
@DisplayName("Action scheduler transaction and concurrency integration tests")
class ActionSchedulerTransactionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ActionScheduler actionScheduler;
    @Autowired private ActionIntentClaimService claimService;
    @Autowired private ActionIntentRepository actionIntentRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignAttemptRepository campaignAttemptRepository;
    @Autowired private ComplianceDecisionRepository complianceDecisionRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ObservingPaymentProvider paymentProvider;

    private final List<UUID> campaignIds = new ArrayList<>();
    private final List<UUID> merchantIds = new ArrayList<>();

    @BeforeEach
    void resetProvider() {
        paymentProvider.reset();
        actionIntentRepository.deleteAll();
    }

    @AfterEach
    void removeFixtures() {
        actionIntentRepository.deleteAll();
        for (UUID campaignId : campaignIds) {
            campaignRepository.findById(campaignId).ifPresent(campaignRepository::delete);
        }
        for (UUID merchantId : merchantIds) {
            merchantRepository.findById(merchantId).ifPresent(merchantRepository::delete);
        }
        campaignIds.clear();
        merchantIds.clear();
    }

    @Test
    void dueReadyIntentIsClaimedInsideARealTransaction() {
        Fixture fixture = createFixture(CampaignStatus.ACTION_PENDING, ActionIntentStatus.READY,
                Instant.now().minusSeconds(5), null);

        List<ActionIntent> claimed = claimService.claimDueIntents("claim-worker", 10);

        assertEquals(1, claimed.size());
        ActionIntent stored = actionIntentRepository.findById(fixture.intent().getId()).orElseThrow();
        assertEquals(ActionIntentStatus.CLAIMED, stored.getStatus());
        assertEquals("claim-worker", stored.getWorkerId());
        assertNotNull(stored.getClaimToken());
        assertNotNull(stored.getClaimedAt());
    }

    @Test
    void futureIntentIsNotClaimed() {
        Fixture fixture = createFixture(CampaignStatus.ACTION_PENDING, ActionIntentStatus.READY,
                Instant.now().plusSeconds(3600), null);

        assertTrue(claimService.claimDueIntents("future-worker", 10).isEmpty());
        assertEquals(ActionIntentStatus.READY,
                actionIntentRepository.findById(fixture.intent().getId()).orElseThrow().getStatus());
    }

    @Test
    void alreadyExecutingIntentIsNotClaimed() {
        Fixture fixture = createFixture(CampaignStatus.EXECUTING, ActionIntentStatus.EXECUTING,
                Instant.now().minusSeconds(5), null);

        assertTrue(claimService.claimDueIntents("late-worker", 10).isEmpty());
        assertEquals(ActionIntentStatus.EXECUTING,
                actionIntentRepository.findById(fixture.intent().getId()).orElseThrow().getStatus());
    }

    @Test
    void recoveredCampaignIntentIsNotClaimed() {
        Fixture fixture = createFixture(CampaignStatus.RECOVERED, ActionIntentStatus.READY,
                Instant.now().minusSeconds(5), null);

        assertTrue(claimService.claimDueIntents("recovered-worker", 10).isEmpty());
        assertEquals(ActionIntentStatus.READY,
                actionIntentRepository.findById(fixture.intent().getId()).orElseThrow().getStatus());
    }

    @Test
    void concurrentWorkersProduceExactlyOneSuccessfulClaim() throws Exception {
        Fixture fixture = createFixture(CampaignStatus.ACTION_PENDING, ActionIntentStatus.READY,
                Instant.now().minusSeconds(5), null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<ActionIntent>> first = executor.submit(() -> {
                start.await();
                return claimService.claimDueIntents("worker-a", 10);
            });
            Future<List<ActionIntent>> second = executor.submit(() -> {
                start.await();
                return claimService.claimDueIntents("worker-b", 10);
            });
            start.countDown();

            int totalClaims = first.get(10, TimeUnit.SECONDS).size()
                    + second.get(10, TimeUnit.SECONDS).size();

            assertEquals(1, totalClaims);
            ActionIntent stored = actionIntentRepository.findById(fixture.intent().getId()).orElseThrow();
            assertEquals(ActionIntentStatus.CLAIMED, stored.getStatus());
            assertTrue("worker-a".equals(stored.getWorkerId()) || "worker-b".equals(stored.getWorkerId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void providerRunsAfterClaimAndExecutingTransactionsCommit() {
        Fixture fixture = createFixture(CampaignStatus.ACTION_PENDING, ActionIntentStatus.READY,
                Instant.now().minusSeconds(5), null);

        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        assertFalse(paymentProvider.transactionActiveDuringCall);
        assertTrue(paymentProvider.claimWasCommitted);
        assertTrue(paymentProvider.executingTransitionWasCommitted);
        assertEquals(ActionIntentStatus.SUCCEEDED,
                actionIntentRepository.findById(fixture.intent().getId()).orElseThrow().getStatus());
        assertEquals(CampaignStatus.RECOVERED,
                campaignRepository.findById(fixture.campaign().getId()).orElseThrow().getCurrentState());
    }

    @Test
    void providerExceptionPersistsErrorForReconciliationWithoutBlindRetry() {
        Fixture fixture = createFixture(CampaignStatus.ACTION_PENDING, ActionIntentStatus.READY,
                Instant.now().minusSeconds(5), null);
        paymentProvider.throwProviderException = true;

        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();
        actionScheduler.pollAndExecuteDueActions();

        assertEquals(1, results.size());
        // M1 behaviour change: a provider exception is an unknown outcome, not an error/failure (invariant 4), see ADR-001
        assertEquals(PaymentExecutionStatus.PENDING, results.get(0).getStatus());
        assertEquals(1, paymentProvider.invocations);
        // M1 behaviour change: unknown outcome leaves the intent EXECUTING (was FAILED); still no blind retry, see ADR-001
        // M2 behaviour change: an unknown outcome is NEEDS_RECONCILIATION (was EXECUTING), handed to the reconciler, see ADR-002
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION,
                actionIntentRepository.findById(fixture.intent().getId()).orElseThrow().getStatus());
        // M1 behaviour change: campaign stays EXECUTING (was FAILED) while the outcome is unknown, see ADR-001
        assertEquals(CampaignStatus.EXECUTING,
                campaignRepository.findById(fixture.campaign().getId()).orElseThrow().getCurrentState());
        List<CampaignAttempt> attempts = campaignAttemptRepository.findByCampaignId(fixture.campaign().getId());
        assertEquals(1, attempts.size());
        // M1 behaviour change: attempt recorded as UNKNOWN (was ERROR), see ADR-001
        assertEquals("UNKNOWN", attempts.get(0).getStatus());
        // M1 behaviour change: the provider receives the attempt key derived from the intent id, see ADR-001
        assertEquals(ProviderAttemptKey.forIntent(fixture.intent().getId()), paymentProvider.lastAttemptKey);
    }

    @Test
    void tenantMismatchCannotBeClaimedOrSentToProvider() {
        UUID otherMerchantId = UUID.randomUUID();
        merchantIds.add(otherMerchantId);
        merchantRepository.save(new Merchant(otherMerchantId, "Scheduler Tenant B"));
        Fixture fixture = createFixture(CampaignStatus.ACTION_PENDING, ActionIntentStatus.READY,
                Instant.now().minusSeconds(5), otherMerchantId);

        assertTrue(actionScheduler.pollAndExecuteDueActions().isEmpty());
        assertEquals(0, paymentProvider.invocations);
        assertEquals(ActionIntentStatus.READY,
                actionIntentRepository.findById(fixture.intent().getId()).orElseThrow().getStatus());
    }

    /**
     * Scenario 6 — scheduledPollAndExecuteDueActions() (the @Scheduled entry point) must
     * complete a full claim→execute cycle WITHOUT throwing
     * InvalidDataAccessApiUsageException / TransactionRequiredException.
     *
     * This is the direct regression guard for the original runtime failure:
     *   ActionScheduler.scheduledPollAndExecuteDueActions()
     *     → pollAndExecuteDueActions()          [self-call, old @Transactional bypassed]
     *     → claimDueIntents()                   [self-call, old @Transactional bypassed]
     *     → claimIntentAtomic()                 [no active tx → BANG]
     *
     * The fix: claim logic lives in ActionIntentClaimService (separate Spring bean),
     * so @Transactional(REQUIRES_NEW) is applied via proxy, not bypassed.
     *
     * Note: application-test.properties sets mend.scheduler.scheduled-enabled=false to
     * prevent the live timer from racing test assertions.  We temporarily re-enable the
     * flag via ReflectionTestUtils so the exact same code path that fires in production
     * is exercised here.
     */
    @Test
    void scheduledEntryPointCompletesWithoutTransactionRequiredException() {
        // Arrange a due READY intent so the scheduler has real work to do
        Fixture fixture = createFixture(CampaignStatus.ACTION_PENDING, ActionIntentStatus.READY,
                Instant.now().minusSeconds(5), null);

        // Temporarily enable the scheduled gate (disabled globally in test profile to
        // prevent timer races; here we want to exercise the exact @Scheduled entry path).
        org.springframework.test.util.ReflectionTestUtils.setField(actionScheduler, "scheduledEnabled", true);
        try {
            // Act — call the @Scheduled entry point directly (same code path the timer fires).
            // If claimIntentAtomic() lacks an active transaction this throws
            // InvalidDataAccessApiUsageException; the test fails on any unchecked exception.
            List<PaymentExecutionResult> results = actionScheduler.scheduledPollAndExecuteDueActions();

            // Assert — one result, intent SUCCEEDED, no exception escaped
            assertEquals(1, results.size(),
                    "scheduledPollAndExecuteDueActions must return results without InvalidDataAccessApiUsageException");
            assertTrue(results.get(0).isSuccess(),
                    "Expected successful execution via scheduledPollAndExecuteDueActions");
            assertEquals(ActionIntentStatus.SUCCEEDED,
                    actionIntentRepository.findById(fixture.intent().getId()).orElseThrow().getStatus());
            assertEquals(CampaignStatus.RECOVERED,
                    campaignRepository.findById(fixture.campaign().getId()).orElseThrow().getCurrentState());
        } finally {
            // Restore the test-profile flag so other tests are unaffected
            org.springframework.test.util.ReflectionTestUtils.setField(actionScheduler, "scheduledEnabled", false);
        }
    }

    /**
     * Scenario 3 (CLAIMED variant) — an intent already in CLAIMED status must not be
     * claimed again by a subsequent scheduler cycle.
     *
     * claimIntentAtomic() requires status = READY; a CLAIMED row must not match, so a
     * second cycle sees zero claimable intents and issues zero provider calls.
     */
    @Test
    void alreadyClaimedIntentIsNotReclaimedBySubsequentCycle() {
        // Arrange — intent already CLAIMED (simulates an in-flight execution)
        Fixture fixture = createFixture(CampaignStatus.ACTION_PENDING, ActionIntentStatus.CLAIMED,
                Instant.now().minusSeconds(5), null);

        // Act — scheduler cycle sees only CLAIMED intent, nothing to claim
        List<PaymentExecutionResult> results = actionScheduler.pollAndExecuteDueActions();

        // Assert — nothing claimed or executed (CLAIMED is not READY)
        assertTrue(results.isEmpty(), "Expected no results when the only intent is already CLAIMED");
        assertEquals(0, paymentProvider.invocations,
                "Provider must not be called when no new intents were claimed");
        assertEquals(ActionIntentStatus.CLAIMED,
                actionIntentRepository.findById(fixture.intent().getId()).orElseThrow().getStatus(),
                "CLAIMED intent status must not change during a competing scheduler cycle");
    }

    private Fixture createFixture(CampaignStatus campaignStatus,
                                  ActionIntentStatus intentStatus,
                                  Instant scheduledAt,
                                  UUID intentMerchantOverride) {
        UUID merchantId = UUID.randomUUID();
        merchantIds.add(merchantId);
        Merchant merchant = new Merchant(merchantId, "Scheduler Transaction Test");
        merchant.setExternalReference("scheduler_tx_" + merchantId);
        merchantRepository.saveAndFlush(merchant);

        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_scheduler_tx_" + UUID.randomUUID());
        campaign.setCurrentState(campaignStatus);
        campaign = campaignRepository.saveAndFlush(campaign);
        campaignIds.add(campaign.getId());

        ComplianceDecisionEntity compliance = new ComplianceDecisionEntity(
                UUID.randomUUID(), campaign.getId(), merchantId, null,
                RecoveryStrategy.RETRY_IMMEDIATELY, ComplianceStatus.COMPLIANCE_ALLOWED,
                ComplianceReason.ALLOWED, "Scheduler transaction test", "scheduler-tx-v1");
        compliance = complianceDecisionRepository.saveAndFlush(compliance);

        UUID intentMerchantId = intentMerchantOverride != null ? intentMerchantOverride : merchantId;
        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(), intentMerchantId, campaign.getId(), 1,
                ActionType.RETRY_PAYMENT.name(), RecoveryStrategy.RETRY_IMMEDIATELY.name(),
                compliance.getId(), intentStatus, "scheduler-tx:" + UUID.randomUUID(), scheduledAt);
        intent.setExpiresAt(scheduledAt.plusSeconds(86400));
        intent = actionIntentRepository.saveAndFlush(intent);
        return new Fixture(campaign, intent);
    }

    private record Fixture(Campaign campaign, ActionIntent intent) {
    }

    @TestConfiguration
    static class ProviderConfiguration {
        @Bean
        @Primary
        ObservingPaymentProvider observingPaymentProvider(ActionIntentRepository actionIntentRepository,
                                                           CampaignRepository campaignRepository) {
            return new ObservingPaymentProvider(actionIntentRepository, campaignRepository);
        }
    }

    // M1: the observing double sits at the new provider seam (mock-mode synthetic debit), see ADR-001
    static class ObservingPaymentProvider implements SyntheticDebitSimulator {
        private final ActionIntentRepository actionIntentRepository;
        private final CampaignRepository campaignRepository;
        private int invocations;
        private boolean transactionActiveDuringCall;
        private boolean claimWasCommitted;
        private boolean executingTransitionWasCommitted;
        private boolean throwProviderException;
        private ProviderAttemptKey lastAttemptKey;

        ObservingPaymentProvider(ActionIntentRepository actionIntentRepository,
                                 CampaignRepository campaignRepository) {
            this.actionIntentRepository = actionIntentRepository;
            this.campaignRepository = campaignRepository;
        }

        @Override
        public RequestResult<String> simulateDebit(MerchantRef merchant, UUID campaignId, UUID intentId, ProviderAttemptKey key) {
            invocations++;
            transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive();
            // M2 behaviour change: the committed claim is observed as the committed dispatch (CLAIMED -> EXECUTING, written
            // before the provider call in the prepare transaction), see ADR-002
            claimWasCommitted = actionIntentRepository.findById(intentId)
                    .map(intent -> intent.getStatus() == ActionIntentStatus.EXECUTING && intent.getDispatchedAt() != null)
                    .orElse(false);
            executingTransitionWasCommitted = campaignRepository.findById(campaignId)
                    .map(campaign -> campaign.getCurrentState() == CampaignStatus.EXECUTING)
                    .orElse(false);
            lastAttemptKey = key;
            if (throwProviderException) {
                throw new RuntimeException("simulated provider timeout");
            }
            return new RequestResult.Accepted<>("pay_mock_schedulertx");
        }

        void reset() {
            invocations = 0;
            transactionActiveDuringCall = false;
            claimWasCommitted = false;
            executingTransitionWasCommitted = false;
            throwProviderException = false;
            lastAttemptKey = null;
        }
    }
}
