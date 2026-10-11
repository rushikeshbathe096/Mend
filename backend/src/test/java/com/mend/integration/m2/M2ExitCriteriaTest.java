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

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M2 U7 — exit criteria (brief §19, §35, §49). Real PostgreSQL, real threads and latches, SQL time travel; no sleeps.
 * Shares ExecutionSafetyScenarioTest's context (same @Import and @MockitoBean) so no extra application context is started;
 * see {@link BlockingProviderConfig} for why that config is top level.
 */
@Import(BlockingProviderConfig.class)
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
    @Autowired private DataSource dataSource;

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

    /** Leaves nothing of this merchant claimable, reconcilable or pending review for later classes on the shared database. */
    private void cancelOwnRows() {
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')", merchantId);
        jdbc.update("UPDATE campaigns SET current_state = 'CANCELLED' WHERE merchant_id = ? "
                + "AND current_state NOT IN ('RECOVERED','EXHAUSTED','FAILED','CANCELLED')", merchantId);
        jdbc.update("UPDATE review_queue SET status = 'CLOSED' WHERE merchant_id = ? AND status = 'PENDING'", merchantId);
        jdbc.update("UPDATE webhook_events SET processing_status = 'PROCESSED' WHERE merchant_id = ? AND processing_status = 'VERIFIED'", merchantId);
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

    /** Fails the test if a worker thread died: the scheduler handles per-intent errors itself, so anything escaping is a bug. */
    private void join(Future<?> future) throws Exception {
        try {
            awaitOrDumpEvidence(future, 20, "worker");
        } catch (ExecutionException crashed) {
            throw new AssertionError("worker thread failed", crashed.getCause());
        }
    }

    /**
     * On a timeout, writes the evidence needed to classify it (thread dump with lock owners, Hikari pool state,
     * pg_stat_activity, ungranted pg_locks) to target/m2-exit-evidence-*.txt before rethrowing. Uses its own
     * DriverManager connection and a file, so neither a starved pool nor a blocked stdout can hide the evidence.
     */
    private void awaitOrDumpEvidence(Future<?> future, long seconds, String what) throws Exception {
        try {
            future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException timedOut) {
            Path file = Path.of("target", "m2-exit-evidence-" + System.currentTimeMillis() + ".txt");
            try {
                Files.writeString(file, evidence(what));
                System.err.println("M2ExitCriteriaTest: timeout evidence written to " + file.toAbsolutePath());
            } catch (IOException | RuntimeException ignored) {
                // evidence is best effort; the timeout itself is still reported
            }
            throw timedOut;
        }
    }

    private String evidence(String what) {
        StringBuilder out = new StringBuilder("timeout waiting for " + what + " at " + Instant.now() + "\n\n== Hikari ==\n");
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            out.append("maximumPoolSize=").append(hikari.getMaximumPoolSize()).append(" minimumIdle=").append(hikari.getMinimumIdle())
                    .append(" connectionTimeout=").append(hikari.getConnectionTimeout())
                    .append(" active=").append(pool.getActiveConnections()).append(" idle=").append(pool.getIdleConnections())
                    .append(" total=").append(pool.getTotalConnections()).append(" waiting=").append(pool.getThreadsAwaitingConnection()).append('\n');
            try (Connection c = DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
                 Statement st = c.createStatement()) {
                out.append("\n== pg_stat_activity ==\n");
                table(out, st.executeQuery("SELECT pid, state, wait_event_type, wait_event, xact_start, query_start, now() - query_start AS running, "
                        + "left(query,120) AS query FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()"));
                out.append("\n== pg_locks WHERE NOT granted ==\n");
                table(out, st.executeQuery("SELECT * FROM pg_locks WHERE NOT granted"));
                out.append("\n== blocking pids ==\n");
                table(out, st.executeQuery("SELECT pid, pg_blocking_pids(pid) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND cardinality(pg_blocking_pids(pid)) > 0"));
            } catch (Exception queryFailed) {
                out.append("database evidence failed: ").append(queryFailed).append('\n');
            }
        }
        out.append("\n== threads ==\n");
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            out.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
            if (info.getLockName() != null) {
                out.append(" on ").append(info.getLockName());
            }
            if (info.getLockOwnerName() != null) {
                out.append(" owned by \"").append(info.getLockOwnerName()).append('"');
            }
            out.append('\n');
            for (StackTraceElement frame : info.getStackTrace()) {
                out.append("    at ").append(frame).append('\n');
            }
            for (var monitor : info.getLockedMonitors()) {
                out.append("    - holds ").append(monitor).append(" (frame ").append(monitor.getLockedStackDepth()).append(")\n");
            }
            for (var sync : info.getLockedSynchronizers()) {
                out.append("    - holds ").append(sync).append('\n');
            }
        }
        return out.toString();
    }

    private static void table(StringBuilder out, ResultSet rows) throws java.sql.SQLException {
        ResultSetMetaData meta = rows.getMetaData();
        while (rows.next()) {
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                out.append(meta.getColumnLabel(i)).append('=').append(rows.getString(i)).append(i < meta.getColumnCount() ? " | " : "\n");
            }
        }
    }

    private long auditCount(Fixture f, String eventType) {
        return auditTrail(f).stream().filter(eventType::equals).count();
    }

    private static boolean linkExists(MockPaymentProvider mock, UUID merchantId, Fixture f) {
        return mock.findPaymentLinkByReference(new MerchantRef(merchantId), f.key()).isFound();
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
            assertEquals(ActionIntentStatus.EXECUTING, reload(f).getStatus(), "lease expiry must not return dispatched work to READY");
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
        assertEquals(RecoveryAttribution.MEND, campaigns.findById(f.campaign().getId()).orElseThrow().getRecoveryAttribution(),
                "U8: recovered through the reconciler on MEND's link");
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

    /**
     * One race: 3 schedulers + the manual path, 2 campaigns sharing an invoice; a 4th thread watches the in-flight count.
     * After the race the provider recovers and one reconciler cycle + one poll run, so SAFE_RETRY resends happen and are
     * counted separately from first sends. The observer polls every ~5 ms and can miss short states; the database index
     * (U6) is the real guarantee, the observer only checks that nothing visible contradicts it.
     */
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
                // A busy loop ran ~65k queries per run (75% of the test log) and was the thread found stuck in the one
                // observer timeout; pausing keeps it to one short-lived connection at a time. See docs/audit/cleanup-report.md.
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
        });

        if (randomReleaseOrder) {
            List<CountDownLatch> order2 = new ArrayList<>(gates);
            Collections.shuffle(order2, random);
            for (CountDownLatch gate : order2) {
                gate.countDown();
                LockSupport.parkNanos(random.nextInt(500_000));   // < 0.5 ms head start, so the order actually varies interleavings
            }
        } else {
            common.countDown();
        }
        for (Future<Object> task : running) {
            join(task);
        }
        Map<Fixture, Integer> firstSends = new HashMap<>();
        for (Fixture f : List.of(x, y)) {
            firstSends.put(f, provider.createCalls(f.key()));
        }

        // The race happened: at least one send, and the outcome left the expected state.
        assertTrue(firstSends.get(x) + firstSends.get(y) >= 1, "nobody executed either intent");
        List<ActionIntentStatus> afterRace = List.of(reload(x).getStatus(), reload(y).getStatus());
        if (outcome != Outcome.REJECTED) {
            ActionIntentStatus expected = outcome == Outcome.SUCCESS ? ActionIntentStatus.AWAITING_PAYMENT : ActionIntentStatus.NEEDS_RECONCILIATION;
            assertTrue(afterRace.contains(expected) && afterRace.contains(ActionIntentStatus.READY),
                    "expected one " + expected + " and one READY sibling, got " + afterRace);
        }

        // The provider recovers; one reconciler cycle and one poll (SAFE_RETRY resends happen here).
        mock.setSimulatedStatus(PaymentExecutionStatus.SUCCESS);
        mock.setSimulatedLostResponse(false);
        for (Fixture f : List.of(x, y)) {
            dispatchedLongAgo(f);   // past the NotFound grace, so an empty lookup is trusted (§7)
            due(f);
        }
        reconciler.runCycle();
        worker("worker-1").pollAndExecuteDueActions();

        done.set(true);
        awaitOrDumpEvidence(observer, 10, "observer");

        assertTrue(maxInFlight.get() <= 1, "observed in-flight intents for one invoice: " + maxInFlight.get());
        long finalInFlight = List.of(reload(x), reload(y)).stream().filter(i -> IN_FLIGHT.contains(i.getStatus().name())).count();
        assertTrue(finalInFlight <= 1, "final in-flight intents for one invoice: " + finalInFlight);
        for (Fixture f : List.of(x, y)) {
            long resends = auditCount(f, "SAFE_RETRY");
            assertTrue(firstSends.get(f) <= 1, "first sends for " + f.key().value() + ": " + firstSends.get(f));
            assertTrue(resends <= 1, "SAFE_RETRY resends for " + f.key().value() + ": " + resends);
            assertTrue(resends == 0 || outcome == Outcome.UNKNOWN,
                    "SAFE_RETRY is only for a send that created nothing (" + outcome + ")");
            assertTrue(provider.createCalls(f.key()) - resends <= 1,
                    "create calls for " + f.key().value() + ": " + provider.createCalls(f.key()) + " of which SAFE_RETRY resends " + resends);
        }
        long totalResends = auditCount(x, "SAFE_RETRY") + auditCount(y, "SAFE_RETRY");
        if (outcome == Outcome.UNKNOWN) {
            assertEquals(1, totalResends, "the Unknown send created nothing, so the reconciler resends it exactly once");
        }
        if (outcome == Outcome.LOST_RESPONSE) {
            assertTrue(List.of(reload(x).getStatus(), reload(y).getStatus()).contains(ActionIntentStatus.AWAITING_PAYMENT),
                    "the lost response's link exists, so the reconciler adopts it instead of resending");
        }
        long linksForInvoice = List.of(x, y).stream().filter(f -> linkExists(mock, merchantId, f)).count();
        assertTrue(linksForInvoice <= 1, "provider resources for one invoice: " + linksForInvoice);
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
        assertEquals(1, auditCount(f, "SAFE_RETRY"), "nothing was at the provider, so exactly one SAFE_RETRY");
        assertEquals(1, provider.createCalls(f.key()), "the dead worker never called; exactly one resend after SAFE_RETRY");
    }
}
