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
        return newReconciler(backoffBase, backoffCap, seed, paymentProvider, 50);
    }

    private ExecutionReconciler newReconciler(Duration backoffBase, Duration backoffCap, long seed, PaymentProvider provider, int batchSize) {
        return new ExecutionReconciler(intents, transitions, provider, capabilities, campaigns, attempts, campaignLifecycleService,
                campaignStateMachine, humanApprovalService, auditService, transactionManager,
                new ExecutionReconciler.Settings(true, false, batchSize, Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(120), 3, 5),
                new ReconcileBackoff(backoffBase, backoffCap, new SplittableRandom(seed)), Clock.systemUTC());
    }

    private void campaignTo(ActionIntent intent, CampaignStatus... path) {
        for (CampaignStatus target : path) {
            campaignLifecycleService.transitionState(merchantId, intent.getCampaignId(), target, "test: drive campaign", "SYSTEM", null);
        }
    }

    private Optional<ReviewQueue> pendingReview(ActionIntent intent) {
        return reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(intent.getCampaignId(), ReviewQueueStatus.PENDING);
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
    @DisplayName("Bug sweep item 4: 2 deferrals + an Unknown outcome still allow SAFE_RETRY (deferrals have their own counter)")
    void deferralsDoNotUseUpTheSafeRetryBudget() {
        ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION);
        mock.setSimulatedNotSent(true);
        for (int deferral = 1; deferral <= 2; deferral++) {
            claimAndExecute(intent);
            ActionIntent deferred = reload(intent);
            assertEquals(ActionIntentStatus.READY, deferred.getStatus(), "deferral " + deferral);
            assertEquals(deferral, deferred.getDeferAttempts());
            assertEquals(0, deferred.getExecutionAttempts(), "a deferred dispatch is not a SAFE_RETRY attempt");
            jdbc.update("UPDATE action_intents SET scheduled_at = now() - interval '1 second' WHERE id = ?", intent.getId());
        }
        mock.setSimulatedNotSent(false);
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);   // Unknown outcome
        claimAndExecute(intent);
        ActionIntent unknown = reload(intent);
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, unknown.getStatus(), "precondition");
        assertEquals(1, unknown.getExecutionAttempts());
        assertEquals(2, unknown.getDeferAttempts());
        assertEquals(3, unknown.getExecutionAttempts() + unknown.getDeferAttempts(), "every dispatch is still counted");

        dispatchedLongAgo(intent);
        due(intent);
        reconciler.runCycle();

        assertEquals(ActionIntentStatus.READY, reload(intent).getStatus(), "SAFE_RETRY, not escalation");
        assertTrue(audited(intent, "SAFE_RETRY"));
        assertFalse(audited(intent, "RECONCILIATION_ESCALATED"));
        assertTrue(pendingReview(intent).isEmpty());
    }

    @Test
    @DisplayName("V17: defer_attempts defaults to 0 and cannot be negative")
    void deferAttemptsColumn() {
        ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION);
        assertEquals(0, reload(intent).getDeferAttempts());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE action_intents SET defer_attempts = -1 WHERE id = ?", intent.getId()));
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
        assertFalse(transitions.safeRetry(intent.getId(), 3, reload(intent).getVersion()), "the UPDATE predicate itself refuses a non-resend-safe action");

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

    // ---- U5 review: remaining table rows ----

    @Test
    @DisplayName("NR + link found PARTIALLY_PAID -> AWAITING_PAYMENT")
    void needsReconciliationFoundPartiallyPaidAwaitsPayment() {
        mock.setSimulatedLostResponse(true);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        mock.setLinkState(key(intent), PaymentLinkState.PARTIALLY_PAID);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, reload(intent).getStatus());
    }

    @Test
    @DisplayName("NR + link found CANCELLED -> FAILED, campaign EXHAUSTED")
    void needsReconciliationFoundCancelledFails() {
        mock.setSimulatedLostResponse(true);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        mock.setLinkState(key(intent), PaymentLinkState.CANCELLED);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.EXHAUSTED, campaignState(intent));
    }

    @Test
    @DisplayName("AWAITING_PAYMENT + link EXPIRED -> FAILED, campaign EXHAUSTED")
    void awaitingPaymentExpiredFails() {
        ActionIntent intent = awaitingPayment();
        mock.setLinkState(key(intent), PaymentLinkState.EXPIRED);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.EXHAUSTED, campaignState(intent));
    }

    // ---- U5 review: terminal-campaign policy, escalation states ----

    @Test
    @DisplayName("Link NotFound after grace on a CANCELLED campaign -> FAILED, no resend, no review (attempts below and at max)")
    void linkNotFoundOnTerminalCampaignFailsWithoutReview() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent belowMax = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        ActionIntent atMax = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        jdbc.update("UPDATE action_intents SET execution_attempts = 3 WHERE id = ?", atMax.getId());
        for (ActionIntent intent : List.of(belowMax, atMax)) {
            campaignTo(intent, CampaignStatus.CANCELLED);
            dispatchedLongAgo(intent);
            due(intent);
        }

        reconciler.runCycle();

        for (ActionIntent intent : List.of(belowMax, atMax)) {
            assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
            assertEquals(CampaignStatus.CANCELLED, campaignState(intent));
            assertTrue(pendingReview(intent).isEmpty(), "nothing for a human to decide");
            assertTrue(audited(intent, "RECONCILER_FAILED"));
            assertEquals(1, providerSends(intent));
        }
    }

    @Test
    @DisplayName("Ambiguous debit on a CANCELLED campaign -> FAILED + AMBIGUOUS_DEBIT review anyway (money may have moved)")
    void ambiguousDebitOnTerminalCampaignStillGetsReview() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = inNeedsReconciliation(ActionType.RETRY_PAYMENT);
        campaignTo(intent, CampaignStatus.CANCELLED);
        jdbc.update("UPDATE action_intents SET reconcile_attempts = 5 WHERE id = ?", intent.getId());
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.CANCELLED, campaignState(intent));
        assertTrue(pendingReview(intent).orElseThrow().getReason().startsWith(ExecutionReconciler.AMBIGUOUS_DEBIT));
    }

    @Test
    @DisplayName("Escalation from a campaign in ACTION_PENDING -> campaign ELIGIBLE + review")
    void escalationFromActionPendingCampaign() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        campaignTo(intent, CampaignStatus.ACTION_PENDING);
        jdbc.update("UPDATE action_intents SET execution_attempts = 3 WHERE id = ?", intent.getId());
        dispatchedLongAgo(intent);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.ELIGIBLE, campaignState(intent));
        assertTrue(pendingReview(intent).orElseThrow().getReason().startsWith(ExecutionReconciler.RECONCILIATION_ESCALATED));
    }

    @Test
    @DisplayName("Captured payment on a campaign that cannot be RECOVERED -> intent SUCCEEDED, audit + review (money surfaced)")
    void capturedOnTerminalCampaignIsSurfaced() {
        ActionIntent intent = awaitingPayment();
        campaignTo(intent, CampaignStatus.CANCELLED);
        mock.payLink(key(intent), PaymentEvidenceStatus.CAPTURED);
        due(intent);

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.SUCCEEDED, reload(intent).getStatus());
        assertEquals(CampaignStatus.CANCELLED, campaignState(intent));
        assertTrue(audited(intent, "CAPTURED_ON_TERMINAL_CAMPAIGN"));
        assertTrue(pendingReview(intent).orElseThrow().getReason().startsWith("CAPTURED_ON_TERMINAL_CAMPAIGN"));
    }

    // ---- U5 review: liveness on errors, version fence, stale worker, settings ----

    @Test
    @DisplayName("An exception while reconciling a row -> backoff + audit RECONCILE_ERROR (no hot loop)")
    void errorWhileReconcilingBacksOff() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        due(intent);
        PaymentProvider exploding = new PaymentProvider() {
            public com.mend.provider.RequestResult<com.mend.provider.PaymentLinkSnapshot> createPaymentLink(com.mend.provider.MerchantRef m, com.mend.provider.CreatePaymentLink c) {
                throw new AssertionError("the reconciler must never create");
            }
            public com.mend.provider.LookupResult<com.mend.provider.PaymentLinkSnapshot> findPaymentLinkByReference(com.mend.provider.MerchantRef m, ProviderAttemptKey k) {
                throw new IllegalStateException("boom");
            }
            public com.mend.provider.LookupResult<com.mend.provider.PaymentLinkSnapshot> getPaymentLink(com.mend.provider.MerchantRef m, String id) {
                throw new IllegalStateException("boom");
            }
            public com.mend.provider.LookupResult<com.mend.provider.PaymentEvidence> getPayment(com.mend.provider.MerchantRef m, String id) {
                throw new IllegalStateException("boom");
            }
        };

        newReconciler(Duration.ofSeconds(30), Duration.ofHours(6), 3, exploding, 50).runCycle();

        assertDeferred(intent, ActionIntentStatus.NEEDS_RECONCILIATION, 1);
        assertTrue(audited(intent, "RECONCILE_ERROR"));
    }

    @Test
    @DisplayName("Reconciler writes are fenced by the version seen after the lease: a concurrent change makes them no-ops")
    void reconcilerWritesRequireTheSeenVersion() {
        mock.setSimulatedStatus(PaymentExecutionStatus.ERROR);
        ActionIntent intent = inNeedsReconciliation(ActionType.REQUEST_CUSTOMER_ACTION);
        long seen = reload(intent).getVersion();
        jdbc.update("UPDATE action_intents SET version = version + 1 WHERE id = ?", intent.getId());

        assertFalse(transitions.deferReconcile(intent.getId(), ActionIntentStatus.NEEDS_RECONCILIATION, Instant.now().plusSeconds(60), seen));
        assertFalse(transitions.safeRetry(intent.getId(), 3, seen));
        assertFalse(transitions.reconcileTo(intent.getId(), ActionIntentStatus.NEEDS_RECONCILIATION, ActionIntentStatus.FAILED, null,
                Instant.now(), null, seen));
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, reload(intent).getStatus());
        assertEquals(0, reload(intent).getReconcileAttempts());
    }

    @Test
    @DisplayName("After the stuck sweep, the presumed-dead worker's finalize is refused")
    void deadWorkerCannotFinalizeAfterStuckSweep() {
        ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION);
        ActionIntent claimed = claimService.claimSpecificIntent(intent.getId(), "slow-worker").orElseThrow();
        campaignTo(intent, CampaignStatus.EXECUTING);
        assertTrue(transitions.dispatch(intent.getId(), claimed.getClaimToken(), Instant.now()));
        dispatchedLongAgo(intent);
        mock.setSimulatedLookupUnavailable(true);

        reconciler.runCycle();
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, reload(intent).getStatus());

        assertFalse(transitions.finalizeFromExecuting(intent.getId(), claimed.getClaimToken(), ActionIntentStatus.AWAITING_PAYMENT,
                "plink_late", null, Instant.now()));
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, reload(intent).getStatus());
    }

    @Test
    @DisplayName("Invalid reconciler settings fail at startup")
    void invalidSettingsAreRejected() {
        assertThrows(IllegalStateException.class, () -> new ExecutionReconciler.Settings(true, true, 0, Duration.ofSeconds(60),
                Duration.ofSeconds(120), Duration.ofSeconds(120), 3, 5));
        assertThrows(IllegalStateException.class, () -> new ExecutionReconciler.Settings(true, true, 50, Duration.ZERO,
                Duration.ofSeconds(120), Duration.ofSeconds(120), 3, 5));
        assertThrows(IllegalStateException.class, () -> new ExecutionReconciler.Settings(true, true, 50, Duration.ofSeconds(60),
                Duration.ofSeconds(120), Duration.ofSeconds(120), 0, 5));
    }

    @Test
    @DisplayName("SKIP LOCKED: rows locked by another transaction are skipped without blocking; the others are processed")
    void lockedRowsAreSkippedNotWaitedFor() throws Exception {
        List<ActionIntent> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION);
            jdbc.update("UPDATE action_intents SET status = 'NEEDS_RECONCILIATION', dispatched_at = now(), execution_attempts = 1, "
                    + "reconcile_after = now() - interval '1 second' WHERE id = ?", intent.getId());
            rows.add(intent);
        }
        List<ActionIntent> held = rows.subList(0, 3);
        List<ActionIntent> free = rows.subList(3, 6);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        org.springframework.transaction.support.TransactionTemplate holder = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try {
            Future<?> lockHolder = threads.submit(() -> holder.executeWithoutResult(status -> {
                for (ActionIntent intent : held) {
                    jdbc.queryForList("SELECT id FROM action_intents WHERE id = ? FOR UPDATE", intent.getId());
                }
                locked.countDown();
                try {
                    releaseLock.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            ExecutionReconciler b = newReconciler(Duration.ofHours(1), Duration.ofHours(1), 9, paymentProvider, 6);
            ExecutionReconciler.Cycle cycle = CompletableFuture.supplyAsync(b::runCycle).get(10, TimeUnit.SECONDS);

            for (ActionIntent intent : free) {
                assertTrue(cycle.picked().contains(intent.getId()));
                assertDeferred(intent, ActionIntentStatus.NEEDS_RECONCILIATION, 1);
            }
            for (ActionIntent intent : held) {
                assertFalse(cycle.picked().contains(intent.getId()), "a locked row must be skipped");
            }
            releaseLock.countDown();
            lockHolder.get(10, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            threads.shutdownNow();
        }
    }
}
