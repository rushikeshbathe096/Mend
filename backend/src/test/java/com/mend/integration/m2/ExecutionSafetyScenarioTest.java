package com.mend.integration.m2;

import com.mend.controller.CampaignController;
import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ComplianceDecisionEntity;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.ComplianceDecisionRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.StartRecoveryRequest;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.mock.MockPaymentProvider;
import com.mend.scheduler.ActionScheduler;
import com.mend.service.ActionIntentClaimService;
import com.mend.service.CampaignLifecycleService;
import com.mend.service.PaymentReconciliationService;
import com.mend.service.RecoveryOrchestratorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M2 U1 — scenario tests for M2-execution-safety.md §2 (S1, S2, S3, S5, S6, S7). Each asserts the TARGET behaviour.
 * Scenarios that fail on the current code are @Disabled("M2-RED: ...") until the unit that fixes them enables them.
 * Real PostgreSQL, real threads, latches; lease expiry is simulated with SQL, never by sleeping.
 */
@Import(ExecutionSafetyScenarioTest.BlockingProviderConfig.class)
class ExecutionSafetyScenarioTest extends AbstractIntegrationTest {

    /** In-flight statuses per M2-execution-safety.md §4 (names, so this compiles before U2 adds the new enum values). */
    private static final Set<String> IN_FLIGHT = Set.of("CLAIMED", "EXECUTING", "NEEDS_RECONCILIATION", "AWAITING_PAYMENT");

    @TestConfiguration
    static class BlockingProviderConfig {
        @Bean
        @Primary
        BlockingPaymentProvider blockingPaymentProvider(MockPaymentProvider mockPaymentProvider) {
            return new BlockingPaymentProvider(mockPaymentProvider);
        }
    }

    @Autowired private ActionScheduler actionScheduler;
    @Autowired private ActionIntentClaimService actionIntentClaimService;
    @Autowired private ActionIntentRepository actionIntentRepository;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ComplianceDecisionRepository complianceDecisionRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private PaymentReconciliationService reconciliationService;
    @Autowired private CampaignController campaignController;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockPaymentProvider mockPaymentProvider;
    @Autowired private BlockingPaymentProvider provider;

    @MockitoBean private RecoveryOrchestratorService recoveryOrchestratorService;

    private ExecutorService threads;
    private UUID merchantId;

    @BeforeEach
    void setUp() {
        threads = Executors.newFixedThreadPool(2);
        provider.reset();
        mockPaymentProvider.reset();
        merchantId = UUID.randomUUID();
        merchantRepository.save(new Merchant(merchantId, "M2 scenario merchant"));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        provider.reset();
        mockPaymentProvider.reset();
        threads.shutdownNow();
        threads.awaitTermination(10, TimeUnit.SECONDS);
    }

    private record Fixture(Campaign campaign, ActionIntent intent, ProviderAttemptKey key) {
    }

    /** Campaign ACTION_PENDING with its original-failure webhook (amount source) and a READY payment-link intent. */
    private Fixture linkIntent(String orderIdOrNull) {
        String paymentId = "pay_m2s" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId(paymentId);
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaignRepository.save(campaign);

        String orderField = orderIdOrNull == null ? "" : ",\"order_id\":\"" + orderIdOrNull + "\"";
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\"" + orderField + "}}}}");
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhookEventRepository.save(event);

        ComplianceDecisionEntity compliance = complianceDecisionRepository.save(new ComplianceDecisionEntity(UUID.randomUUID(),
                campaign.getId(), merchantId, null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED,
                ComplianceReason.ALLOWED, "Compliant action", "v1.0.0"));
        ActionIntent intent = actionIntentRepository.save(new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1,
                ActionType.REQUEST_CUSTOMER_ACTION.name(), RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), compliance.getId(),
                ActionIntentStatus.READY, "intent:" + campaign.getId() + ":attempt_1:REQUEST_CUSTOMER_ACTION",
                Instant.now().minusSeconds(5)));
        return new Fixture(campaign, intent, ProviderAttemptKey.forIntent(intent.getId()));
    }

    private ActionIntent reload(ActionIntent intent) {
        return actionIntentRepository.findById(intent.getId()).orElseThrow();
    }

    private CampaignStatus campaignState(Campaign campaign) {
        return campaignRepository.findById(campaign.getId()).orElseThrow().getCurrentState();
    }

    /** Simulates lease expiry without sleeping: move claimed_at back past the 5-minute lease. */
    private void expireLease(ActionIntent intent) {
        jdbcTemplate.update("UPDATE action_intents SET claimed_at = claimed_at - interval '10 minutes' WHERE id = ?", intent.getId());
    }

    /** Worker A: a real scheduler poll, held inside the provider call for {@code key}. */
    private Future<?> workerABlockedInProviderCall(ProviderAttemptKey key) throws InterruptedException {
        provider.blockFirstCreate(key);
        Future<?> a = threads.submit(() -> actionScheduler.pollAndExecuteDueActions());
        assertTrue(provider.awaitEntered(key), "worker A never reached the provider call");
        return a;
    }

    @Test
    @Disabled("M2-RED: S1 manual start-recovery claims by plain save and dispatches a second create call (2 != 1)")
    @DisplayName("S1 manual start-recovery while the scheduler's provider call is in flight -> exactly one provider call")
    void s1ManualPathDoesNotDuplicateInFlightCall() throws Exception {
        // Given: worker A (scheduler) claimed the intent and is inside the provider call.
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());
        when(recoveryOrchestratorService.orchestrateRecovery(any(), any())).thenReturn(reload(f.intent()));

        // When: the manual start-recovery path runs for the same campaign.
        StartRecoveryRequest request = new StartRecoveryRequest();
        request.setPaymentId(f.campaign().getPaymentId());
        campaignController.startRecovery(request, merchantId.toString(), null);
        provider.release(f.key());
        a.get(10, TimeUnit.SECONDS);

        // Then: the provider saw one create call for the attempt key.
        assertEquals(1, provider.createCalls(f.key()));
    }

    @Test
    @Disabled("M2-RED: S2 lease release returns a dispatched intent to READY behind an EXECUTING campaign")
    @DisplayName("S2 worker dies after dispatch, lease expires -> intent is not orphaned as READY with campaign EXECUTING")
    void s2CrashAfterDispatchIsNotOrphaned() throws Exception {
        // Given: worker A dispatched and is (effectively) dead inside the provider call.
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());

        // When: the lease expires and the release job runs.
        expireLease(f.intent());
        actionIntentClaimService.releaseExpiredClaims();

        // Then: the intent is not READY behind an EXECUTING campaign (unclaimable forever, never reconciled).
        ActionIntent intent = reload(f.intent());
        boolean orphan = intent.getStatus() == ActionIntentStatus.READY && campaignState(f.campaign()) == CampaignStatus.EXECUTING;
        provider.release(f.key());
        a.get(10, TimeUnit.SECONDS);
        assertFalse(orphan, "intent orphaned: READY with campaign EXECUTING");
    }

    @Test
    @Disabled("M2-RED: S3 OutcomeUnknown leaves the intent EXECUTING with no reconciliation")
    @DisplayName("S3 provider outcome unknown -> intent is handed to reconciliation, not left EXECUTING")
    void s3UnknownOutcomeIsScheduledForReconciliation() {
        // Given: the provider times out (OutcomeUnknown).
        Fixture f = linkIntent(null);
        mockPaymentProvider.setSimulatedStatus(PaymentExecutionStatus.ERROR);

        // When: the scheduler executes the intent.
        actionScheduler.pollAndExecuteDueActions();

        // Then: the intent waits for reconciliation instead of sitting in EXECUTING with nothing to resolve it.
        assertEquals("NEEDS_RECONCILIATION", reload(f.intent()).getStatus().name());
    }

    @Test
    @Disabled("M2-RED: S5 released in-flight intent is re-claimed and dispatched again (2 != 1)")
    @DisplayName("S5 released in-flight intent and campaign back in ACTION_PENDING -> no second provider call for the attempt")
    void s5StaleReadyIntentIsNotReExecuted() throws Exception {
        // Given: worker A dispatched and hangs; the lease expires and the release job runs.
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());
        expireLease(f.intent());
        actionIntentClaimService.releaseExpiredClaims();

        // When: the campaign re-enters ACTION_PENDING through allowed transitions and worker B polls.
        campaignLifecycleService.transitionState(merchantId, f.campaign().getId(), CampaignStatus.FAILED, "test: drive to retry", "SYSTEM", null);
        campaignLifecycleService.transitionState(merchantId, f.campaign().getId(), CampaignStatus.ACTION_PENDING, "test: retry", "SYSTEM", null);
        threads.submit(() -> actionScheduler.pollAndExecuteDueActions()).get(10, TimeUnit.SECONDS);
        int callsAfterB = provider.createCalls(f.key());
        provider.release(f.key());
        a.get(10, TimeUnit.SECONDS);

        // Then: worker B made no second create call for the same attempt key.
        assertEquals(1, callsAfterB);
    }

    @Test
    @DisplayName("S6a webhook finalizes the intent while worker A is mid-call -> A's later result is discarded")
    void s6aWebhookWinsOverStaleWorkerResult() throws Exception {
        // Given: worker A is inside the provider call.
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());

        // When: a captured-payment webhook correlated to the intent arrives, then A finishes.
        WebhookEvent captured = new WebhookEvent(UUID.randomUUID(), "evt_m2s6a_" + UUID.randomUUID(), "payment.captured", "RAZORPAY",
                "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_m2s6aCaptured\",\"status\":\"captured\","
                        + "\"notes\":{\"mend_intent_id\":\"" + f.intent().getId() + "\"}}}}}");
        captured.setMerchantId(merchantId);
        reconciliationService.reconcileWebhookEvent(webhookEventRepository.save(captured));
        provider.release(f.key());
        a.get(10, TimeUnit.SECONDS);

        // Then: the webhook's evidence stands; A's link result did not overwrite it.
        ActionIntent intent = reload(f.intent());
        assertEquals(ActionIntentStatus.SUCCEEDED, intent.getStatus());
        assertEquals("pay_m2s6aCaptured", intent.getResponseReference());
        assertEquals(CampaignStatus.RECOVERED, campaignState(f.campaign()));
    }

    @Test
    @Disabled("M2-RED: S6 stale worker finalize writes EXECUTING onto an intent whose claim was released")
    @DisplayName("S6b claim released while worker A is mid-call -> A's result is never applied to an intent A no longer owns")
    void s6bStaleWorkerCannotWriteAfterLosingClaim() throws Exception {
        // Given: worker A is inside the provider call.
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());

        // When: the lease expires, the release job runs, then A finishes and finalizes.
        expireLease(f.intent());
        actionIntentClaimService.releaseExpiredClaims();
        provider.release(f.key());
        a.get(10, TimeUnit.SECONDS);

        // Then: either the release did not apply (A still owns it and finalized), or A's write was discarded (still READY).
        ActionIntent intent = reload(f.intent());
        boolean staleWriteApplied = intent.getClaimToken() == null && intent.getStatus() != ActionIntentStatus.READY;
        assertFalse(staleWriteApplied, "stale worker wrote " + intent.getStatus() + " to an intent whose claim had been released");
    }

    @Test
    @Disabled("M2-RED: S7 two intents for one order are both in flight (2 > 1)")
    @DisplayName("S7 two campaigns for the same order (not a recording) -> at most one intent in flight")
    void s7AtMostOneInFlightPerInvoice() {
        // Given: two campaigns whose original failure payloads share an order_id (hand-built, not a recording).
        String sharedOrder = "order_M2s7" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Fixture first = linkIntent(sharedOrder);
        Fixture second = linkIntent(sharedOrder);

        // When: the scheduler runs.
        actionScheduler.pollAndExecuteDueActions();

        // Then: at most one of the two intents is in flight.
        long inFlight = List.of(reload(first.intent()), reload(second.intent())).stream()
                .filter(intent -> IN_FLIGHT.contains(intent.getStatus().name()))
                .count();
        assertTrue(inFlight <= 1, "in-flight intents for one invoice: " + inFlight);
    }
}
