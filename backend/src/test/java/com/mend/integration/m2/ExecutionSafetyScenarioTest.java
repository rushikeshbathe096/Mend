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
import org.springframework.context.annotation.Import;
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
@Import(BlockingProviderConfig.class)
class ExecutionSafetyScenarioTest extends AbstractIntegrationTest {

    /** In-flight statuses per M2-execution-safety.md §4 (names, so this compiles before U2 adds the new enum values). */
    private static final Set<String> IN_FLIGHT = Set.of("CLAIMED", "EXECUTING", "NEEDS_RECONCILIATION", "AWAITING_PAYMENT");

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
    @Autowired private com.mend.service.ExecutionReconciler executionReconciler;
    @Autowired private com.mend.service.ActionExecutionService executor;
    @Autowired private com.mend.domain.repository.AuditLogRepository auditLogRepository;

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
        threads.shutdownNow();
        threads.awaitTermination(10, TimeUnit.SECONDS);
        mockPaymentProvider.reset();
    }

    /** Joins worker A; its own outcome is not what these tests assert, so an exception inside A is ignored. */
    private static void join(Future<?> worker) throws InterruptedException {
        try {
            worker.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // worker A's result is irrelevant to the scenario assertion
        }
    }

    private record Fixture(Campaign campaign, ActionIntent intent, ProviderAttemptKey key) {
    }

    /** Campaign ACTION_PENDING with its original-failure webhook (amount source) and a READY payment-link intent. */
    private Fixture linkIntent(String orderIdOrNull) {
        String paymentId = "pay_m2s" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String orderField = orderIdOrNull == null ? "" : ",\"order_id\":\"" + orderIdOrNull + "\"";
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\"" + orderField + "}}}}");
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhookEventRepository.save(event);

        // M2 U6: the campaign is created by the production path, which derives invoice_key from the stored failure webhook.
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, null, null);
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaignRepository.save(campaign);

        ComplianceDecisionEntity compliance = complianceDecisionRepository.save(new ComplianceDecisionEntity(UUID.randomUUID(),
                campaign.getId(), merchantId, null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED,
                ComplianceReason.ALLOWED, "Compliant action", "v1.0.0"));
        // Scheduled long ago so the scheduler's batch (ordered by scheduled_at, max 50) always includes it, whatever
        // other test classes left behind in the shared database.
        ActionIntent newIntent = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1,
                ActionType.REQUEST_CUSTOMER_ACTION.name(), RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), compliance.getId(),
                ActionIntentStatus.READY, "intent:" + campaign.getId() + ":attempt_1:REQUEST_CUSTOMER_ACTION",
                Instant.EPOCH.plusSeconds(Math.floorMod(campaign.getId().getMostSignificantBits(), 1_000_000L)));
        newIntent.setInvoiceKey(campaign.getInvoiceKey());   // as ActionIntentService.createActionIntentFromCompliance does
        ActionIntent intent = actionIntentRepository.save(newIntent);
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
    @DisplayName("S1 manual start-recovery while the scheduler's provider call is in flight -> exactly one provider call")
    void s1ManualPathDoesNotDuplicateInFlightCall() throws Exception {
        // Given: worker A (scheduler) claimed the intent and is inside the provider call.
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());
        when(recoveryOrchestratorService.orchestrateRecovery(any(), any())).thenReturn(reload(f.intent()));

        // When: the manual start-recovery path runs for the same campaign (a rejection/conflict is an acceptable answer).
        int calls;
        try {
            StartRecoveryRequest request = new StartRecoveryRequest();
            request.setPaymentId(f.campaign().getPaymentId());
            try {
                campaignController.startRecovery(request, merchantId.toString(), null);
            } catch (RuntimeException rejected) {
                // the manual path may refuse to execute an intent it could not claim
            }
            calls = provider.createCalls(f.key());
        } finally {
            provider.release(f.key());
        }
        join(a);

        // Then: the provider saw one create call for the attempt key.
        assertEquals(1, calls);
    }

    @Test
    @DisplayName("S2 worker dies after dispatch, lease expires -> intent is not orphaned as READY with campaign EXECUTING")
    void s2CrashAfterDispatchIsNotOrphaned() throws Exception {
        // Given: worker A dispatched and is (effectively) dead inside the provider call.
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());

        // When: the lease expires and the release job runs.
        expireLease(f.intent());
        actionIntentClaimService.releaseExpiredClaims();

        // Then: the intent is not READY behind an EXECUTING campaign (unclaimable forever, never reconciled).
        // (Eventual resolution via the stuck sweep is asserted once U5 adds the reconciler.)
        boolean orphan;
        try {
            ActionIntent intent = reload(f.intent());
            orphan = intent.getStatus() == ActionIntentStatus.READY && campaignState(f.campaign()) == CampaignStatus.EXECUTING;
        } finally {
            provider.release(f.key());
        }
        join(a);
        assertFalse(orphan, "intent orphaned: READY with campaign EXECUTING");
    }

    @Test
    @DisplayName("S2 (U5) worker dies after dispatch -> reconciler resolves it; the late worker result is discarded; one link only")
    void s2CrashAfterDispatchIsResolvedByTheReconciler() throws Exception {
        // Given: worker A dispatched and is stuck inside the provider call (the request has not reached the provider yet).
        jdbcTemplate.update("UPDATE action_intents SET reconcile_after = now() + interval '30 days' "
                + "WHERE status IN ('NEEDS_RECONCILIATION', 'AWAITING_PAYMENT')");
        jdbcTemplate.update("UPDATE action_intents SET dispatched_at = now() WHERE status = 'EXECUTING'");
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());
        try {
            // When: T_stuck passes and the reconciler runs.
            jdbcTemplate.update("UPDATE action_intents SET dispatched_at = dispatched_at - interval '10 minutes', "
                    + "claimed_at = claimed_at - interval '10 minutes' WHERE id = ?", f.intent().getId());
            executionReconciler.runCycle();

            // Then: stuck -> NEEDS_RECONCILIATION -> nothing at the provider -> SAFE_RETRY (same intent, same key).
            assertEquals(ActionIntentStatus.READY, reload(f.intent()).getStatus());
            assertEquals(CampaignStatus.ACTION_PENDING, campaignState(f.campaign()));
        } finally {
            provider.release(f.key());
        }
        join(a);

        // Worker A's late result cannot touch the intent it no longer owns.
        assertEquals(ActionIntentStatus.READY, reload(f.intent()).getStatus());
        assertTrue(auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(f.campaign().getId()).stream()
                .anyMatch(log -> "STALE_WORKER_RESULT_DISCARDED".equals(log.getEventType())));

        // The resend carries the same reference: the provider returns A's link, it never creates a second one.
        var lookup = mockPaymentProvider.findPaymentLinkByReference(new com.mend.provider.MerchantRef(merchantId), f.key());
        assertTrue(lookup.isFound(), "worker A's request landed after it was released");
        String linkFromA = ((com.mend.provider.LookupResult.Found<com.mend.provider.PaymentLinkSnapshot>) lookup).value().providerLinkId();
        ActionIntent reclaimed = actionIntentClaimService.claimSpecificIntent(f.intent().getId(), "worker-b").orElseThrow();
        executor.executeActionIntent(f.intent().getId(), "worker-b", reclaimed.getClaimToken());

        ActionIntent after = reload(f.intent());
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, after.getStatus());
        assertEquals(linkFromA, after.getResponseReference());
        assertEquals(2, provider.createCalls(f.key()), "two sends, one link (reference_id uniqueness)");
    }

    @Test
    @DisplayName("S3 provider outcome unknown -> intent is handed to reconciliation, not left EXECUTING")
    void s3UnknownOutcomeIsScheduledForReconciliation() {
        // Given: the provider times out (OutcomeUnknown).
        Fixture f = linkIntent(null);
        mockPaymentProvider.setSimulatedStatus(PaymentExecutionStatus.ERROR);

        // When: the scheduler executes the intent.
        actionScheduler.pollAndExecuteDueActions();

        // Then: the intent waits for reconciliation instead of sitting in EXECUTING with nothing to resolve it.
        ActionIntent intent = reload(f.intent());
        assertNull(intent.getResponseReference(), "precondition: the outcome was Unknown (a LinkIssued outcome would store a plink id)");
        assertEquals("NEEDS_RECONCILIATION", intent.getStatus().name());
    }

    @Test
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
        int callsAfterB;
        try {
            join(threads.submit(() -> actionScheduler.pollAndExecuteDueActions()));
            callsAfterB = provider.createCalls(f.key());
        } finally {
            provider.release(f.key());
        }
        join(a);

        // Then: worker B made no second create call for the same attempt key.
        assertEquals(1, callsAfterB);
    }

    @Test
    @DisplayName("S6a webhook finalizes the intent while worker A is mid-call -> A's later result is discarded")
    // Regression guard: relies on a captured webhook resolving an intent that is still mid-execution. If a later unit
    // restricts webhook finalization by intent state, changing this is a design decision, not a test edit.
    void s6aWebhookWinsOverStaleWorkerResult() throws Exception {
        // Given: worker A is inside the provider call.
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());

        // When: a captured-payment webhook correlated to the intent arrives, then A finishes.
        WebhookEvent captured = new WebhookEvent(UUID.randomUUID(), "evt_m2s6a_" + UUID.randomUUID(), "payment.captured", "RAZORPAY",
                "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_m2s6aCaptured\",\"status\":\"captured\","
                        + "\"notes\":{\"mend_intent_id\":\"" + f.intent().getId() + "\"}}}}}");
        captured.setMerchantId(merchantId);
        try {
            reconciliationService.reconcileWebhookEvent(webhookEventRepository.save(captured));
        } finally {
            provider.release(f.key());
        }
        join(a);

        // Then: the webhook's evidence stands; A's link result did not overwrite it.
        ActionIntent intent = reload(f.intent());
        assertEquals(ActionIntentStatus.SUCCEEDED, intent.getStatus());
        assertEquals("pay_m2s6aCaptured", intent.getResponseReference());
        assertEquals(CampaignStatus.RECOVERED, campaignState(f.campaign()));
    }

    @Test
    @DisplayName("S6b claim released while worker A is mid-call -> A's result is never applied to an intent A no longer owns")
    void s6bStaleWorkerCannotWriteAfterLosingClaim() throws Exception {
        // Given: worker A is inside the provider call.
        Fixture f = linkIntent(null);
        Future<?> a = workerABlockedInProviderCall(f.key());

        // When: the lease expires, the release job runs, then A finishes and finalizes.
        ActionIntent afterRelease;
        try {
            expireLease(f.intent());
            actionIntentClaimService.releaseExpiredClaims();
            afterRelease = reload(f.intent());
        } finally {
            provider.release(f.key());
        }
        join(a);

        // Then: if the release took A's claim away (READY), A's later result must not be written onto the intent.
        ActionIntent intent = reload(f.intent());
        if (afterRelease.getStatus() == ActionIntentStatus.READY) {
            assertEquals(ActionIntentStatus.READY, intent.getStatus(),
                    "stale worker wrote " + intent.getStatus() + " to an intent whose claim had been released");
            assertEquals(afterRelease.getResponseReference(), intent.getResponseReference());
        }
    }

    @Test
    @DisplayName("S7 two campaigns for the same order (not a recording) -> at most one intent in flight")
    void s7AtMostOneInFlightPerInvoice() {
        // Given: two campaigns whose original failure payloads share an order_id (hand-built, not a recording).
        String sharedOrder = "order_M2s7" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Fixture first = linkIntent(sharedOrder);
        Fixture second = linkIntent(sharedOrder);
        assertEquals("ord:" + sharedOrder, first.campaign().getInvoiceKey(), "precondition: production derivation");
        assertEquals(first.campaign().getInvoiceKey(), second.campaign().getInvoiceKey());

        // When: the scheduler runs.
        actionScheduler.pollAndExecuteDueActions();

        // Then: exactly one of the two intents is in flight and the other is still waiting (not failed, not lost).
        List<ActionIntent> intents = List.of(reload(first.intent()), reload(second.intent()));
        long inFlight = intents.stream().filter(intent -> IN_FLIGHT.contains(intent.getStatus().name())).count();
        assertEquals(1, inFlight, "in-flight intents for one invoice: " + inFlight);
        assertTrue(intents.stream().anyMatch(intent -> intent.getStatus() == ActionIntentStatus.READY),
                "the blocked intent must stay READY, got " + intents.stream().map(i -> i.getStatus().name()).toList());
    }
}
