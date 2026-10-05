package com.mend.service;

import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.provider.PaymentEvidenceStatus;
import com.mend.provider.PaymentLinkState;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.ProviderCapabilities;
import com.mend.provider.mock.MockPaymentProvider;
import com.mend.statemachine.CampaignStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 U5: one test per row of the §7 decision table (mock provider), the stuck sweep, SAFE_RETRY, debit escalation,
 * liveness after a crash, and two reconcilers racing over the same rows (SKIP LOCKED + reconcile lease).
 * Time is simulated with SQL updates on dispatched_at / reconcile_after.
 */
@DisplayName("M2 U5: ExecutionReconciler (mock provider)")
class ExecutionReconcilerTest extends AbstractIntegrationTest {

    @Autowired private ActionIntentRepository intents;
    @Autowired private ActionIntentTransitions transitions;
    @Autowired private PaymentProvider paymentProvider;
    @Autowired private ProviderCapabilities capabilities;
    @Autowired private CampaignRepository campaigns;
    @Autowired private CampaignAttemptRepository attempts;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private CampaignStateMachine campaignStateMachine;
    @Autowired private HumanApprovalService humanApprovalService;
    @Autowired private AuditService auditService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ActionIntentClaimService claimService;
    @Autowired private ActionExecutionService executor;
    @Autowired private MerchantRepository merchants;
    @Autowired private ComplianceDecisionRepository complianceDecisions;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private ReviewQueueRepository reviews;
    @Autowired private MockPaymentProvider mock;
    @Autowired private JdbcTemplate jdbc;

    private UUID merchantId;
    private ExecutionReconciler reconciler;

    @BeforeEach
    void setUp() {
        mock.reset();
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M2 reconciler merchant"));
        // Shared database: park rows other test classes left in reconcile states so this test only sees its own rows.
        jdbc.update("UPDATE action_intents SET reconcile_after = now() + interval '30 days' "
                + "WHERE status IN ('NEEDS_RECONCILIATION', 'AWAITING_PAYMENT')");
        jdbc.update("UPDATE action_intents SET dispatched_at = now() WHERE status = 'EXECUTING' AND merchant_id <> ?", merchantId);
        reconciler = newReconciler(Duration.ofSeconds(30), Duration.ofHours(6), 42);
    }

    @AfterEach
    void tearDown() {
        mock.reset();
    }

    private ExecutionReconciler newReconciler(Duration backoffBase, Duration backoffCap, long seed) {
        return new ExecutionReconciler(intents, transitions, paymentProvider, capabilities, campaigns, attempts, campaignLifecycleService,
                campaignStateMachine, humanApprovalService, auditService, transactionManager,
                new ExecutionReconciler.Settings(true, false, 50, Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(120), 3, 5),
                new ReconcileBackoff(backoffBase, backoffCap, new SplittableRandom(seed)), Clock.systemUTC());
    }

    // ---- fixtures ----

    /** Campaign ACTION_PENDING + original-failure webhook (amount source) + compliance + READY intent of the given type. */
    private ActionIntent readyIntent(ActionType type) {
        String paymentId = "pay_m2r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId(paymentId);
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaigns.save(campaign);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\"}}}}");
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhooks.save(event);
        ComplianceDecisionEntity compliance = complianceDecisions.save(new ComplianceDecisionEntity(UUID.randomUUID(), campaign.getId(),
                merchantId, null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED, ComplianceReason.ALLOWED,
                "Compliant action", "v1.0.0"));
        return intents.save(new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, type.name(),
                RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), compliance.getId(), ActionIntentStatus.READY,
                "intent:" + campaign.getId() + ":attempt_1:" + type.name(), Instant.now().minusSeconds(5)));
    }

    /** The real manual path: single-intent claim + executor (dispatch fence, provider call, fenced finalize). */
    private void claimAndExecute(ActionIntent intent) {
        ActionIntent claimed = claimService.claimSpecificIntent(intent.getId(), "reconciler-test-worker").orElseThrow();
        executor.executeActionIntent(intent.getId(), "reconciler-test-worker", claimed.getClaimToken());
    }

    private ActionIntent inNeedsReconciliation(ActionType type) {
        ActionIntent intent = readyIntent(type);
        claimAndExecute(intent);
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, reload(intent).getStatus(), "precondition");
        return intent;
    }

    private ActionIntent awaitingPayment() {
        ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION);
        claimAndExecute(intent);
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, reload(intent).getStatus(), "precondition");
        return intent;
    }

    private ActionIntent reload(ActionIntent intent) {
        return intents.findById(intent.getId()).orElseThrow();
    }

    private CampaignStatus campaignState(ActionIntent intent) {
        return campaigns.findById(intent.getCampaignId()).orElseThrow().getCurrentState();
    }

    private ProviderAttemptKey key(ActionIntent intent) {
        return ProviderAttemptKey.forIntent(intent.getId());
    }

    private long providerSends(ActionIntent intent) {
        return mock.getRecordedRequests().stream()
                .filter(call -> key(intent).equals(call.attemptKey()))
                .filter(call -> call.operation().equals("create_payment_link") || call.operation().equals("synthetic_debit"))
                .count();
    }

    private boolean audited(ActionIntent intent, String eventType) {
        return auditLogs.findByCampaignIdOrderByCreatedAtAsc(intent.getCampaignId()).stream()
                .anyMatch(log -> eventType.equals(log.getEventType()));
    }

    private void due(ActionIntent intent) {
        jdbc.update("UPDATE action_intents SET reconcile_after = now() - interval '1 second' WHERE id = ?", intent.getId());
    }

    private void dispatchedLongAgo(ActionIntent intent) {
        jdbc.update("UPDATE action_intents SET dispatched_at = dispatched_at - interval '10 minutes', "
                + "claimed_at = claimed_at - interval '10 minutes' WHERE id = ?", intent.getId());
    }

    private void assertDeferred(ActionIntent intent, ActionIntentStatus expected, int reconcileAttempts) {
        ActionIntent after = reload(intent);
        assertEquals(expected, after.getStatus());
        assertEquals(reconcileAttempts, after.getReconcileAttempts());
        assertTrue(after.getReconcileAfter().isAfter(Instant.now()), "a row that does not progress must get a later reconcile_after");
    }

    // ---- §7: NEEDS_RECONCILIATION ----

    @Test
    @DisplayName("NR + link found CREATED -> AWAITING_PAYMENT with the plink id, no resend")
    void needsReconciliationFoundCreatedBecomesAwaitingPayment() {
        mock.setSimulatedLostResponse(true);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        due(intent);

        reconciler.runCycle();

        ActionIntent after = reload(intent);
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, after.getStatus());
        assertEquals("plink_mock_" + key(intent).value().replace("mnd_", ""), after.getResponseReference());
        assertEquals(1, after.getReconcileAttempts());
        assertTrue(after.getReconcileAfter().isAfter(Instant.now()));
        assertEquals(CampaignStatus.EXECUTING, campaignState(intent), "a link is not money");
        assertEquals(1, providerSends(intent));
        assertTrue(audited(intent, "RECONCILED_AWAITING_PAYMENT"));
    }

    @Test
    @DisplayName("NR + link found PAID with a CAPTURED payment -> SUCCEEDED, campaign RECOVERED")
    void needsReconciliationFoundPaidCapturedSucceeds() {
        mock.setSimulatedLostResponse(true);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        String paymentId = mock.payLink(key(intent), PaymentEvidenceStatus.CAPTURED);
        due(intent);

        reconciler.runCycle();

        ActionIntent after = reload(intent);
        assertEquals(ActionIntentStatus.SUCCEEDED, after.getStatus());
        assertEquals(paymentId, after.getResponseReference());
        assertNotNull(after.getCompletedAt());
        assertEquals(CampaignStatus.RECOVERED, campaignState(intent));
        assertTrue(audited(intent, "RECONCILER_SUCCEEDED"));
    }

    @Test
    @DisplayName("NR + link found EXPIRED -> FAILED, campaign failure path (EXHAUSTED)")
    void needsReconciliationFoundExpiredFails() {
        mock.setSimulatedLostResponse(true);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        mock.setLinkState(key(intent), PaymentLinkState.EXPIRED);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.EXHAUSTED, campaignState(intent));
        assertTrue(audited(intent, "RECONCILER_FAILED"));
    }

    @Test
    @DisplayName("NR + NotFound within the grace period -> stays, backoff, no resend")
    void needsReconciliationNotFoundWithinGraceStays() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        due(intent);

        reconciler.runCycle();

        assertDeferred(intent, ActionIntentStatus.NEEDS_RECONCILIATION, 1);
        assertEquals(1, providerSends(intent));
    }

    @Test
    @DisplayName("NR + NotFound after grace -> SAFE_RETRY (same intent, same key) -> resend issues the link")
    void needsReconciliationNotFoundAfterGraceSafeRetries() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        dispatchedLongAgo(intent);
        due(intent);

        reconciler.runCycle();

        ActionIntent retried = reload(intent);
        assertEquals(ActionIntentStatus.READY, retried.getStatus());
        assertNull(retried.getClaimToken());
        assertNull(retried.getDispatchedAt());
        assertEquals(CampaignStatus.ACTION_PENDING, campaignState(intent));
        assertTrue(audited(intent, "SAFE_RETRY"));

        mock.setSimulatedStatus(PaymentExecutionStatus.SUCCESS);
        claimAndExecute(intent);

        ActionIntent after = reload(intent);
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, after.getStatus());
        assertEquals(2, after.getExecutionAttempts());
        assertEquals(2, providerSends(intent), "both sends carry the same attempt key");
    }

    @Test
    @DisplayName("NR + NotFound after grace with dispatches exhausted -> FAILED + review RECONCILIATION_ESCALATED, campaign ELIGIBLE")
    void needsReconciliationNotFoundWithAttemptsExhaustedEscalates() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        jdbc.update("UPDATE action_intents SET execution_attempts = 3 WHERE id = ?", intent.getId());
        dispatchedLongAgo(intent);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.ELIGIBLE, campaignState(intent));
        ReviewQueue review = reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(intent.getCampaignId(), ReviewQueueStatus.PENDING)
                .orElseThrow();
        assertTrue(review.getReason().startsWith(ExecutionReconciler.RECONCILIATION_ESCALATED));
        assertTrue(audited(intent, ExecutionReconciler.RECONCILIATION_ESCALATED));
        assertEquals(1, providerSends(intent));
    }

    @Test
    @DisplayName("NR + lookup Unavailable -> stays, backoff (never treated as NotFound)")
    void needsReconciliationUnavailableStays() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        dispatchedLongAgo(intent);
        mock.setSimulatedLookupUnavailable(true);
        due(intent);

        reconciler.runCycle();

        assertDeferred(intent, ActionIntentStatus.NEEDS_RECONCILIATION, 1);
        assertEquals(CampaignStatus.EXECUTING, campaignState(intent));
    }

    // ---- §7: AWAITING_PAYMENT ----

    @Test
    @DisplayName("AWAITING_PAYMENT + link PAID with a CAPTURED payment -> SUCCEEDED, campaign RECOVERED (webhook fallback)")
    void awaitingPaymentPaidCapturedSucceeds() {
        ActionIntent intent = awaitingPayment();
        String paymentId = mock.payLink(key(intent), PaymentEvidenceStatus.CAPTURED);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.SUCCEEDED, reload(intent).getStatus());
        assertEquals(paymentId, reload(intent).getResponseReference());
        assertEquals(CampaignStatus.RECOVERED, campaignState(intent));
    }

    @Test
    @DisplayName("AWAITING_PAYMENT + link still CREATED (payment only authorized) -> stays, backoff")
    void awaitingPaymentCreatedStays() {
        ActionIntent intent = awaitingPayment();
        mock.payLink(key(intent), PaymentEvidenceStatus.AUTHORIZED_NOT_CAPTURED);
        due(intent);

        reconciler.runCycle();

        assertDeferred(intent, ActionIntentStatus.AWAITING_PAYMENT, 1);
        assertEquals(CampaignStatus.EXECUTING, campaignState(intent));
    }

    @Test
    @DisplayName("AWAITING_PAYMENT + link CANCELLED -> FAILED, campaign EXHAUSTED")
    void awaitingPaymentCancelledFails() {
        ActionIntent intent = awaitingPayment();
        mock.setLinkState(key(intent), PaymentLinkState.CANCELLED);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.EXHAUSTED, campaignState(intent));
    }

    @Test
    @DisplayName("AWAITING_PAYMENT + link NotFound -> NEEDS_RECONCILIATION + audit (should not happen)")
    void awaitingPaymentNotFoundGoesBackToReconciliation() {
        ActionIntent intent = awaitingPayment();
        mock.reset();
        due(intent);

        reconciler.runCycle();

        assertDeferred(intent, ActionIntentStatus.NEEDS_RECONCILIATION, 1);
        assertTrue(audited(intent, "RECONCILE_LINK_NOT_FOUND"));
    }

    // ---- debit: never SAFE_RETRY ----

    @Test
    @DisplayName("Debit ambiguity: never READY, deferred up to the budget, then FAILED + review AMBIGUOUS_DEBIT")
    void ambiguousDebitIsNeverResentAndEndsInReview() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = inNeedsReconciliation(ActionType.RETRY_PAYMENT);
        dispatchedLongAgo(intent);
        assertFalse(transitions.safeRetry(intent.getId(), 3), "the UPDATE predicate itself refuses a non-resend-safe action");

        for (int cycle = 1; cycle <= 5; cycle++) {
            due(intent);
            reconciler.runCycle();
            assertDeferred(intent, ActionIntentStatus.NEEDS_RECONCILIATION, cycle);
        }
        due(intent);
        reconciler.runCycle();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.ELIGIBLE, campaignState(intent));
        ReviewQueue review = reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(intent.getCampaignId(), ReviewQueueStatus.PENDING)
                .orElseThrow();
        assertTrue(review.getReason().startsWith(ExecutionReconciler.AMBIGUOUS_DEBIT));
        assertFalse(audited(intent, "SAFE_RETRY"));
        assertEquals(1, providerSends(intent), "an ambiguous debit is never sent twice");
    }

    // ---- stuck EXECUTING + liveness ----

    /** Worker claimed and dispatched (EXECUTING, campaign EXECUTING), then died before finalizing. */
    private ActionIntent crashedAfterDispatch(boolean requestReachedProvider) {
        ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION);
        ActionIntent claimed = claimService.claimSpecificIntent(intent.getId(), "doomed-worker").orElseThrow();
        campaignLifecycleService.transitionState(merchantId, intent.getCampaignId(), CampaignStatus.EXECUTING, "test: execution started", "SYSTEM", null);
        assertTrue(transitions.dispatch(intent.getId(), claimed.getClaimToken(), Instant.now()));
        if (requestReachedProvider) {
            paymentProvider.createPaymentLink(new com.mend.provider.MerchantRef(merchantId), new com.mend.provider.CreatePaymentLink(key(intent),
                    new com.mend.provider.MoneyMinor(49900, "INR"), "test", null, intent.getId()));
        }
        return intent;
    }

    @Test
    @DisplayName("Liveness: crash after dispatch, request never reached the provider -> stuck sweep, SAFE_RETRY, resend, recovered within 2 cycles")
    void crashAfterDispatchIsResolvedWithinBoundedCycles() {
        ActionIntent intent = crashedAfterDispatch(false);
        dispatchedLongAgo(intent);

        ExecutionReconciler.Cycle first = reconciler.runCycle();

        assertEquals(1, first.markedStuck());
        assertTrue(audited(intent, "EXECUTION_STUCK"));
        assertEquals(ActionIntentStatus.READY, reload(intent).getStatus(), "nothing at the provider: SAFE_RETRY");
        assertEquals(CampaignStatus.ACTION_PENDING, campaignState(intent));

        claimAndExecute(intent);
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, reload(intent).getStatus());
        mock.payLink(key(intent), PaymentEvidenceStatus.CAPTURED);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.SUCCEEDED, reload(intent).getStatus());
        assertEquals(CampaignStatus.RECOVERED, campaignState(intent));
    }

    @Test
    @DisplayName("Liveness: crash after the request reached the provider -> stuck sweep finds the link, no resend")
    void crashAfterProviderReceivedRequestFindsTheLink() {
        ActionIntent intent = crashedAfterDispatch(true);
        dispatchedLongAgo(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, reload(intent).getStatus());
        assertEquals(1, providerSends(intent));
    }

    @Test
    @DisplayName("EXECUTING younger than T_stuck is left alone")
    void recentExecutingIsNotMarkedStuck() {
        ActionIntent intent = crashedAfterDispatch(false);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.EXECUTING, reload(intent).getStatus());
    }

    // ---- two reconcilers ----

    @Test
    @DisplayName("Two reconcilers concurrently: each due row is processed exactly once per lease (SKIP LOCKED)")
    void twoReconcilersProcessEachRowOnce() throws Exception {
        List<ActionIntent> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION);
            jdbc.update("UPDATE action_intents SET status = 'NEEDS_RECONCILIATION', dispatched_at = now(), execution_attempts = 1, "
                    + "reconcile_after = now() - interval '1 second' WHERE id = ?", intent.getId());
            rows.add(intent);
        }
        // Long backoff so a deferred row cannot become due again while the other reconciler is still running.
        ExecutionReconciler a = newReconciler(Duration.ofHours(1), Duration.ofHours(1), 1);
        ExecutionReconciler b = newReconciler(Duration.ofHours(1), Duration.ofHours(1), 2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ExecutionReconciler.Cycle> fa = threads.submit(() -> { start.await(); return a.runCycle(); });
            Future<ExecutionReconciler.Cycle> fb = threads.submit(() -> { start.await(); return b.runCycle(); });
            start.countDown();
            List<UUID> pickedA = fa.get(30, TimeUnit.SECONDS).picked();
            List<UUID> pickedB = fb.get(30, TimeUnit.SECONDS).picked();

            Set<UUID> overlap = new HashSet<>(pickedA);
            overlap.retainAll(pickedB);
            assertTrue(overlap.isEmpty(), "rows picked by both reconcilers: " + overlap);
            for (ActionIntent row : rows) {
                assertTrue(pickedA.contains(row.getId()) || pickedB.contains(row.getId()), "row never picked: " + row.getId());
                assertDeferred(row, ActionIntentStatus.NEEDS_RECONCILIATION, 1);
            }
        } finally {
            threads.shutdownNow();
        }
    }
}
