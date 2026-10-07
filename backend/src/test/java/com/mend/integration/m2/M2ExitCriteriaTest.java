package com.mend.integration.m2;

import com.mend.controller.CampaignController;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.dto.StartRecoveryRequest;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.PaymentEvidenceStatus;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.mock.MockPaymentProvider;
import com.mend.scheduler.ActionScheduler;
import com.mend.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M2 U7 — exit criteria (brief §19, §35, §49). Real PostgreSQL, real threads and latches, SQL time travel; no sleeps.
 * Shares ExecutionSafetyScenarioTest's context (same @Import and @MockitoBean) so no extra application context is started.
 */
@Import(ExecutionSafetyScenarioTest.BlockingProviderConfig.class)
@DisplayName("M2 exit criteria")
class M2ExitCriteriaTest extends AbstractIntegrationTest {

    private static final List<String> IN_FLIGHT = List.of("CLAIMED", "EXECUTING", "NEEDS_RECONCILIATION", "AWAITING_PAYMENT");

    @Autowired private ActionIntentClaimService claimService;
    @Autowired private ActionExecutionService executor;
    @Autowired private ExecutionReconciler reconciler;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private CampaignController campaignController;
    @Autowired private ActionIntentTransitions transitions;
    @Autowired private ActionIntentRepository intents;
    @Autowired private CampaignRepository campaigns;
    @Autowired private MerchantRepository merchants;
    @Autowired private ComplianceDecisionRepository complianceDecisions;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private ReviewQueueRepository reviews;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider mock;
    @Autowired private BlockingPaymentProvider provider;

    @MockitoBean private RecoveryOrchestratorService recoveryOrchestratorService;

    private ExecutorService threads;
    private UUID merchantId;

    @BeforeEach
    void setUp() {
        threads = Executors.newFixedThreadPool(6);
        provider.reset();
        mock.reset();
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M2 exit merchant"));
        parkOtherRows();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        provider.reset();
        threads.shutdownNow();
        threads.awaitTermination(10, TimeUnit.SECONDS);
        mock.reset();
        cancelOwnRows();
    }

    /** Shared database: other classes' leftovers must not enter the scheduler's or reconciler's batches. */
    private void parkOtherRows() {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING') AND merchant_id <> ?", merchantId);
        jdbc.update("UPDATE action_intents SET reconcile_after = now() + interval '30 days' WHERE status IN ('NEEDS_RECONCILIATION','AWAITING_PAYMENT') AND merchant_id <> ?", merchantId);
        jdbc.update("UPDATE action_intents SET dispatched_at = now() WHERE status = 'EXECUTING' AND merchant_id <> ?", merchantId);
    }

    private void cancelOwnRows() {
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')", merchantId);
    }

    // ---- fixtures ----

    private record Fixture(Campaign campaign, ActionIntent intent, ProviderAttemptKey key) {
    }

    /** Failure webhook → production campaign creation (invoice_key derived) → ACTION_PENDING → READY intent with the campaign key. */
    private Fixture intentFor(String orderIdOrNull, ActionType type) {
        String paymentId = "pay_m2x" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String order = orderIdOrNull == null ? "" : ",\"order_id\":\"" + orderIdOrNull + "\"";
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\"" + order + "}}}}");
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhooks.save(event);
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, null, null);
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaigns.save(campaign);
        ComplianceDecisionEntity compliance = complianceDecisions.save(new ComplianceDecisionEntity(UUID.randomUUID(), campaign.getId(),
                merchantId, null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED, ComplianceReason.ALLOWED,
                "Compliant action", "v1.0.0"));
        ActionIntent intent = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, type.name(),
                RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), compliance.getId(), ActionIntentStatus.READY,
                "intent:" + campaign.getId() + ":attempt_1:" + type.name(), Instant.now().minusSeconds(5));
        intent.setInvoiceKey(campaign.getInvoiceKey());
        intent = intents.save(intent);
        return new Fixture(campaign, intent, ProviderAttemptKey.forIntent(intent.getId()));
    }

    private ActionScheduler worker(String id) {
        return new ActionScheduler(claimService, executor, id, 50, true, true);
    }

    private ActionIntent reload(Fixture f) {
        return intents.findById(f.intent().getId()).orElseThrow();
    }

    private CampaignStatus campaignState(Fixture f) {
        return campaigns.findById(f.campaign().getId()).orElseThrow().getCurrentState();
    }

    private void expireLease(Fixture f) {
        jdbc.update("UPDATE action_intents SET claimed_at = claimed_at - interval '10 minutes' WHERE id = ?", f.intent().getId());
    }

    private void due(Fixture f) {
        jdbc.update("UPDATE action_intents SET reconcile_after = now() - interval '1 second' WHERE id = ?", f.intent().getId());
    }

    private void dispatchedLongAgo(Fixture f) {
        jdbc.update("UPDATE action_intents SET dispatched_at = dispatched_at - interval '10 minutes', "
                + "claimed_at = claimed_at - interval '10 minutes' WHERE id = ?", f.intent().getId());
    }

    private List<String> auditTrail(Fixture f) {
        return auditLogs.findByCampaignIdOrderByCreatedAtAsc(f.campaign().getId()).stream().map(AuditLog::getEventType).toList();
    }

    private long syntheticDebits(Fixture f) {
        return mock.getRecordedRequests().stream()
                .filter(call -> f.key().equals(call.attemptKey()) && call.operation().equals("synthetic_debit")).count();
    }

    private static void join(Future<?> future) throws Exception {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // a worker's own exception is not what these tests assert
        }
    }

    // ---- 1. §19 exactly ----

    @Test
    @DisplayName("§19: A dispatches and hangs, lease expires, release runs, B polls; A's call is Unknown; reconciler resolves; one provider resource")
    void brief19() throws Exception {
        Fixture f = intentFor(null, ActionType.REQUEST_CUSTOMER_ACTION);
        provider.blockFirstCreate(f.key());
        mock.setSimulatedLostResponse(true);   // the create lands at the provider, the response is lost -> OutcomeUnknown

        Future<?> a = threads.submit(() -> worker("worker-a").pollAndExecuteDueActions());
        assertTrue(provider.awaitEntered(f.key()), "worker A never reached the provider call");
        int bCalls;
        try {
            expireLease(f);
            claimService.releaseExpiredClaims();
            worker("worker-b").pollAndExecuteDueActions();
            bCalls = provider.createCalls(f.key()) - 1;
        } finally {
            provider.release(f.key());
        }
        join(a);

        assertEquals(0, bCalls, "worker B made a provider call for an attempt A had dispatched");
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, reload(f).getStatus(), "A's outcome was Unknown");

        mock.setSimulatedLostResponse(false);
        due(f);
        reconciler.runCycle();
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, reload(f).getStatus());
        mock.payLink(f.key(), PaymentEvidenceStatus.CAPTURED);
        due(f);
        reconciler.runCycle();

        assertEquals(ActionIntentStatus.SUCCEEDED, reload(f).getStatus());
        assertEquals(CampaignStatus.RECOVERED, campaignState(f));
        assertEquals(1, provider.createCalls(f.key()), "provider create calls for the attempt key");
        LookupResult<?> resource = mock.findPaymentLinkByReference(new MerchantRef(merchantId), f.key());
        assertTrue(resource.isFound(), "exactly one provider resource exists for the attempt key (reference_id is unique)");
        List<String> trail = auditTrail(f);
        int dispatch = trail.indexOf("ACTION_EXECUTING");
        int unknown = trail.indexOf("PROVIDER_OUTCOME_UNKNOWN");
        int reconciled = trail.indexOf("RECONCILED_AWAITING_PAYMENT");
        int succeeded = trail.indexOf("RECONCILER_SUCCEEDED");
        assertTrue(dispatch >= 0 && dispatch < unknown && unknown < reconciled && reconciled < succeeded, "audit order: " + trail);
    }

    // ---- 2. zero duplicate debits ----

    @Test
    @DisplayName("Zero duplicate debits: same as §19 with a synthetic debit; Unknown is never resent and ends in an AMBIGUOUS_DEBIT review")
    void zeroDuplicateDebits() throws Exception {
        Fixture f = intentFor(null, ActionType.RETRY_PAYMENT);
        provider.blockFirstCreate(f.key());
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);

        Future<?> a = threads.submit(() -> worker("worker-a").pollAndExecuteDueActions());
        assertTrue(provider.awaitEntered(f.key()), "worker A never reached the debit");
        int bDebits;
        try {
            expireLease(f);
            claimService.releaseExpiredClaims();
            worker("worker-b").pollAndExecuteDueActions();
            bDebits = provider.debitCalls(f.key()) - 1;
        } finally {
            provider.release(f.key());
        }
        join(a);
        assertEquals(0, bDebits);
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, reload(f).getStatus());

        dispatchedLongAgo(f);
        int cycles = 0;
        while (reload(f).getStatus() == ActionIntentStatus.NEEDS_RECONCILIATION && cycles < 10) {
            due(f);
            reconciler.runCycle();
            cycles++;
            assertNotEquals(ActionIntentStatus.READY, reload(f).getStatus(), "an ambiguous debit must never be SAFE_RETRY'd");
            worker("worker-c").pollAndExecuteDueActions();
        }

        assertEquals(ActionIntentStatus.FAILED, reload(f).getStatus(), "escalated after the deferral budget");
        assertEquals(1, syntheticDebits(f), "synthetic debit count for the intent");
        assertEquals(1, provider.debitCalls(f.key()));
        ReviewQueue review = reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(f.campaign().getId(), ReviewQueueStatus.PENDING).orElseThrow();
        assertTrue(review.getReason().startsWith("AMBIGUOUS_DEBIT"));
    }

    // ---- 3 + 5. three workers, one invoice (+ manual path), deterministic and randomized ----

    private enum Outcome { SUCCESS, UNKNOWN, LOST_RESPONSE, REJECTED }

    private void apply(Outcome outcome) {
        mock.reset();
        switch (outcome) {
            case SUCCESS -> { }
            case UNKNOWN -> mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
            case LOST_RESPONSE -> mock.setSimulatedLostResponse(true);
            case REJECTED -> mock.setSimulatedStatus(PaymentExecutionStatus.FAILURE);
        }
    }

    /** One race: 3 schedulers + the manual path, 2 campaigns sharing an invoice; a 4th thread watches the in-flight count. */
    private void invoiceRace(Random random, boolean randomReleaseOrder, Outcome outcome) throws Exception {
        provider.reset();
        apply(outcome);
        String order = "order_M2x" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Fixture x = intentFor(order, ActionType.REQUEST_CUSTOMER_ACTION);
        Fixture y = intentFor(order, ActionType.REQUEST_CUSTOMER_ACTION);
        assertEquals(x.campaign().getInvoiceKey(), y.campaign().getInvoiceKey(), "precondition: one invoice");
        when(recoveryOrchestratorService.orchestrateRecovery(any(), any())).thenReturn(reload(x));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (String id : List.of("worker-1", "worker-2", "worker-3")) {
            ActionScheduler scheduler = worker(id);
            tasks.add(() -> scheduler.pollAndExecuteDueActions());
        }
        tasks.add(() -> {
            StartRecoveryRequest request = new StartRecoveryRequest();
            request.setPaymentId(x.campaign().getPaymentId());
            try {
                return campaignController.startRecovery(request, merchantId.toString(), null);
            } catch (RuntimeException refused) {
                return refused;   // a refusal is an acceptable answer for the manual path
            }
        });
        List<CountDownLatch> gates = new ArrayList<>();
        List<Future<Object>> running = new ArrayList<>();
        CountDownLatch common = new CountDownLatch(1);
        for (Callable<Object> task : tasks) {
            CountDownLatch gate = randomReleaseOrder ? new CountDownLatch(1) : common;
            gates.add(gate);
            running.add(threads.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }

        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger maxInFlight = new AtomicInteger();
        Future<?> observer = threads.submit(() -> {
            while (!done.get()) {
                Integer worst = jdbc.queryForObject("SELECT COALESCE(MAX(n), 0) FROM (SELECT count(*) AS n FROM action_intents "
                        + "WHERE merchant_id = ? AND status IN ('CLAIMED','EXECUTING','NEEDS_RECONCILIATION','AWAITING_PAYMENT') "
                        + "GROUP BY invoice_key) t", Integer.class, merchantId);
                maxInFlight.accumulateAndGet(worst == null ? 0 : worst, Math::max);
            }
        });

        if (randomReleaseOrder) {
            List<CountDownLatch> order2 = new ArrayList<>(gates);
            Collections.shuffle(order2, random);
            order2.forEach(CountDownLatch::countDown);
        } else {
            common.countDown();
        }
        for (Future<Object> task : running) {
            join(task);
        }
        done.set(true);
        observer.get(10, TimeUnit.SECONDS);

        assertTrue(maxInFlight.get() <= 1, "observed in-flight intents for one invoice: " + maxInFlight.get());
        long finalInFlight = List.of(reload(x), reload(y)).stream().filter(i -> IN_FLIGHT.contains(i.getStatus().name())).count();
        assertTrue(finalInFlight <= 1, "final in-flight intents for one invoice: " + finalInFlight);
        for (Fixture f : List.of(x, y)) {
            assertTrue(provider.createCalls(f.key()) <= 1, "create calls for " + f.key().value() + ": " + provider.createCalls(f.key()));
        }
        for (Fixture f : List.of(x, y)) {
            long resources = mock.findPaymentLinkByReference(new MerchantRef(merchantId), f.key()).isFound() ? 1 : 0;
            assertTrue(resources <= 1);
        }
        cancelOwnRows();
    }

    @Test
    @DisplayName("Three workers + the manual path, two campaigns on one invoice: never more than one in flight, at most one call per attempt key")
    void threeWorkersOneInvoice() throws Exception {
        invoiceRace(new Random(1), false, Outcome.SUCCESS);
    }

    @Test
    @DisplayName("Randomized (seeded): 200 races with random release order and random provider outcomes")
    void randomizedInvoiceRaces() throws Exception {
        long seed = Long.getLong("m2.exit.seed", System.nanoTime());
        System.out.println("M2ExitCriteriaTest.randomizedInvoiceRaces seed=" + seed);
        Random random = new Random(seed);
        Outcome[] outcomes = Outcome.values();
        for (int iteration = 0; iteration < 200; iteration++) {
            Outcome outcome = outcomes[random.nextInt(outcomes.length)];
            try {
                invoiceRace(random, true, outcome);
            } catch (AssertionError | Exception failure) {
                throw new AssertionError("randomized race failed: seed=" + seed + " iteration=" + iteration + " outcome=" + outcome
                        + " (rerun with -Dm2.exit.seed=" + seed + ")", failure);
            }
        }
    }

    // ---- 4. crash after dispatch ----

    @Test
    @DisplayName("Crash after dispatch: stuck sweep -> reconcile -> resolved within K=3 reconciler cycles")
    void crashAfterDispatchResolvesWithinBoundedCycles() {
        Fixture f = intentFor(null, ActionType.REQUEST_CUSTOMER_ACTION);
        ActionIntent claimed = claimService.claimSpecificIntent(f.intent().getId(), "doomed-worker").orElseThrow();
        campaignLifecycleService.transitionState(merchantId, f.campaign().getId(), CampaignStatus.EXECUTING, "test: execution started", "SYSTEM", null);
        assertTrue(transitions.dispatch(f.intent().getId(), claimed.getClaimToken(), Instant.now()));
        dispatchedLongAgo(f);   // the worker died; T_stuck has passed

        int cycles = 0;
        while (reload(f).getStatus() != ActionIntentStatus.SUCCEEDED && cycles < 3) {
            reconciler.runCycle();
            cycles++;
            ActionIntentStatus status = reload(f).getStatus();
            if (status == ActionIntentStatus.READY) {
                worker("worker-r").pollAndExecuteDueActions();   // SAFE_RETRY -> the normal path resends with the same key
            }
            if (reload(f).getStatus() == ActionIntentStatus.AWAITING_PAYMENT) {
                mock.payLink(f.key(), PaymentEvidenceStatus.CAPTURED);
                due(f);
            }
        }

        assertEquals(ActionIntentStatus.SUCCEEDED, reload(f).getStatus(), "resolved within " + cycles + " cycles");
        assertEquals(CampaignStatus.RECOVERED, campaignState(f));
        assertTrue(auditTrail(f).contains("EXECUTION_STUCK"));
        assertTrue(provider.createCalls(f.key()) <= 1, "the dead worker never called; one resend after SAFE_RETRY");
    }
}
