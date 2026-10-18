package com.mend.integration.m2;

import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.service.ActionIntentClaimService;
import com.mend.service.ActionIntentService;
import com.mend.service.ActionIntentTransitions;
import com.mend.service.CampaignLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** M2 U6: invoice_key on campaign creation and intent creation, and the DB-enforced one-in-flight-per-invoice rule. */
@DisplayName("M2 U6: one in-flight intent per invoice")
class OneInFlightPerInvoiceTest extends AbstractIntegrationTest {

    @Autowired
    private com.mend.domain.repository.ClassificationResultRepository classificationResultRepository;

    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private ActionIntentService actionIntentService;
    @Autowired private ActionIntentClaimService claimService;
    @Autowired private com.mend.service.DecisionLogService decisionLogService;
    @Autowired private ActionIntentTransitions transitions;
    @Autowired private ActionIntentRepository intents;
    @Autowired private CampaignRepository campaigns;
    @Autowired private MerchantRepository merchants;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private MerchantConfigRepository merchantConfigs;
    @Autowired private ClassificationResultRepository classifications;
    @Autowired private com.mend.service.RecoveryStrategyService recoveryStrategyService;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M2 U6 merchant"));
    }

    /** Shared database: leave no claimable or in-flight rows behind for other test classes. */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')", merchantId);
    }

    private String storeFailure(String orderIdOrNull) {
        String paymentId = "pay_m2u6" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String order = orderIdOrNull == null ? "" : ",\"order_id\":\"" + orderIdOrNull + "\"";
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\"" + order + "}}}}");
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhooks.save(event);
        return paymentId;
    }

    /** Production campaign creation (derives invoice_key), then a READY intent carrying the campaign's key. */
    private ActionIntent readyIntentFor(String paymentId) {
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, null, null);
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaigns.save(campaign);
        ActionIntent intent = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, ActionType.REQUEST_CUSTOMER_ACTION.name(),
                RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), null, ActionIntentStatus.READY, "intent:" + UUID.randomUUID(),
                Instant.now().minusSeconds(5));
        intent.setInvoiceKey(campaign.getInvoiceKey());
        return intents.save(intent);
    }

    private ActionIntent reload(ActionIntent intent) {
        return intents.findById(intent.getId()).orElseThrow();
    }

    @Test
    @DisplayName("Campaign creation derives invoice_key from the stored failure webhook; fallback without it")
    void campaignCreationDerivesTheKey() {
        String order = "order_M2u6" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String withOrder = storeFailure(order);
        String withoutOrder = storeFailure(null);

        assertEquals("ord:" + order, campaignLifecycleService.getOrCreateCampaign(merchantId, withOrder, null, null).getInvoiceKey());
        assertEquals("pay:" + withoutOrder, campaignLifecycleService.getOrCreateCampaign(merchantId, withoutOrder, null, null).getInvoiceKey());
        Campaign noWebhook = campaignLifecycleService.getOrCreateCampaign(merchantId, "manual_" + UUID.randomUUID(), null, null);
        assertEquals("cmp:" + noWebhook.getId(), noWebhook.getInvoiceKey());
    }

    @Test
    @DisplayName("createActionIntentFromCompliance copies the campaign's invoice_key onto the intent")
    void intentCreationCopiesTheKey() {
        String order = "order_M2u6c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String paymentId = storeFailure(order);
        WebhookEvent event = webhooks.findByMerchantIdAndExternalEventId(merchantId, paymentId).orElseThrow();
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setEnabledRecoveryActions("ALL");
        merchantConfigs.saveAndFlush(config);
        // Same compliance context as Phase20's flow: classification -> CLASSIFIED -> ELIGIBLE -> persisted strategy.
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, null, null);
        classifications.saveAndFlush(com.mend.testsupport.TestClassifications.deterministic(new ClassificationResult(null, event.getId(), campaign.getId(), "INSUFFICIENT_FUNDS",
                java.math.BigDecimal.valueOf(0.95), "RETRY_PAYMENT", "Balance issue", "v1.0.0")));
        campaign.setCurrentState(CampaignStatus.CLASSIFIED);
        campaign.setConfidence(java.math.BigDecimal.valueOf(0.95));
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign = campaigns.saveAndFlush(campaign);
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "test", "SYSTEM", null);
        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());

        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());

        assertEquals("ord:" + order, intent.getInvoiceKey());
        assertEquals("ord:" + order, reload(intent).getInvoiceKey());
    }

    @Test
    @DisplayName("The database rejects a second in-flight intent for the same (merchant, invoice_key) (direct SQL)")
    void indexRejectsDirectSqlViolation() {
        String order = "order_M2u6s" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ActionIntent a = readyIntentFor(storeFailure(order));
        ActionIntent b = readyIntentFor(storeFailure(order));
        jdbc.update("UPDATE action_intents SET status = 'AWAITING_PAYMENT' WHERE id = ?", a.getId());

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE action_intents SET status = 'CLAIMED' WHERE id = ?", b.getId()));
        jdbc.update("UPDATE action_intents SET status = 'SUCCEEDED' WHERE id = ?", a.getId());
        assertEquals(1, jdbc.update("UPDATE action_intents SET status = 'CLAIMED' WHERE id = ?", b.getId()), "allowed once A is terminal");
    }

    @Test
    @DisplayName("Claim refuses an intent whose invoice already has an in-flight intent (predicate), and allows it later")
    void claimPredicateBlocksSibling() {
        String order = "order_M2u6p" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ActionIntent a = readyIntentFor(storeFailure(order));
        ActionIntent b = readyIntentFor(storeFailure(order));

        assertTrue(claimService.claimSpecificIntent(a.getId(), "w1").isPresent());
        assertTrue(claimService.claimSpecificIntent(b.getId(), "w2").isEmpty());
        assertEquals(ActionIntentStatus.READY, reload(b).getStatus());
    }

    @Test
    @DisplayName("Two threads claim two intents of one invoice concurrently -> exactly one is claimed")
    void concurrentClaimsOnOneInvoice() throws Exception {
        String order = "order_M2u6r" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ActionIntent a = readyIntentFor(storeFailure(order));
        ActionIntent b = readyIntentFor(storeFailure(order));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<ActionIntent>> fa = threads.submit(() -> { start.await(); return claimService.claimSpecificIntent(a.getId(), "wa"); });
            Future<Optional<ActionIntent>> fb = threads.submit(() -> { start.await(); return claimService.claimSpecificIntent(b.getId(), "wb"); });
            start.countDown();
            int claimed = (fa.get(20, TimeUnit.SECONDS).isPresent() ? 1 : 0) + (fb.get(20, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertEquals(1, claimed);
        } finally {
            threads.shutdownNow();
        }
        long inFlight = List.of(reload(a), reload(b)).stream().filter(intent -> intent.getStatus().isInFlight()).count();
        assertEquals(1, inFlight);
    }

    @Test
    @DisplayName("True race (both pass the predicate): the unique index decides; the loser is not claimed and is audited")
    void uniqueIndexDecidesATrueRace() throws Exception {
        String order = "order_M2u6t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ActionIntent a = readyIntentFor(storeFailure(order));
        ActionIntent b = readyIntentFor(storeFailure(order));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch aClaimedUncommitted = new CountDownLatch(1);
        CountDownLatch commitA = new CountDownLatch(1);
        try {
            // A claims inside a transaction that stays open: B's predicate cannot see A's uncommitted claim.
            int[] backendA = new int[1];
            Future<Boolean> fa = threads.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                boolean claimed = transitions.claim(a.getId(), "wa", UUID.randomUUID().toString(), Instant.now());
                backendA[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                aClaimedUncommitted.countDown();
                try {
                    commitA.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return claimed;
            }));
            assertTrue(aClaimedUncommitted.await(10, TimeUnit.SECONDS));
            Future<Optional<ActionIntent>> fb = threads.submit(() -> claimService.claimSpecificIntent(b.getId(), "wb"));

            // Wait until B is blocked on the index entry of A's uncommitted row, then let A commit.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            // Only a waiter blocked by A's backend counts (not any unrelated lock wait in the cluster).
            while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))",
                    Integer.class, backendA[0]) == 0) {
                assertTrue(System.nanoTime() < deadline, "B never blocked on A's uncommitted claim");
                Thread.onSpinWait();
            }
            commitA.countDown();

            assertTrue(fa.get(20, TimeUnit.SECONDS));
            assertTrue(fb.get(20, TimeUnit.SECONDS).isEmpty(), "the race loser must not be claimed");
        } finally {
            commitA.countDown();
            threads.shutdownNow();
        }
        assertEquals(ActionIntentStatus.CLAIMED, reload(a).getStatus());
        assertEquals(ActionIntentStatus.READY, reload(b).getStatus());
        assertTrue(auditLogs.findByCampaignIdOrderByCreatedAtAsc(b.getCampaignId()).stream()
                .anyMatch(log -> "CLAIM_BLOCKED_INFLIGHT".equals(log.getEventType())));
    }

    @Autowired private com.mend.service.AuditService auditService;

    @Test
    @DisplayName("Blocked intents do not fill the claim batch: a free intent is still claimed behind more blocked ones than the batch size")
    void blockedIntentsDoNotStarveTheBatch() {
        ActionIntentClaimService smallBatch = new ActionIntentClaimService(intents, transitions, auditService, 2, 5, decisionLogService);
        // Shared database: keep other classes' READY rows out of this small batch.
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status = 'READY' AND merchant_id <> ?", merchantId);
        for (int i = 0; i < 3; i++) {
            String order = "order_M2u6b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            ActionIntent inFlight = readyIntentFor(storeFailure(order));
            assertTrue(claimService.claimSpecificIntent(inFlight.getId(), "holder").isPresent());
            ActionIntent blocked = readyIntentFor(storeFailure(order));
            jdbc.update("UPDATE action_intents SET scheduled_at = now() - interval '1 day' WHERE id = ?", blocked.getId());
        }
        ActionIntent free = readyIntentFor(storeFailure(null));

        List<ActionIntent> claimed = smallBatch.claimDueIntents("batch-worker", 2);

        assertTrue(claimed.stream().anyMatch(intent -> intent.getId().equals(free.getId())),
                "the free intent must be claimed although 3 older blocked intents exceed the batch of 2");
    }
}
