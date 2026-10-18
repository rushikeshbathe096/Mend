package com.mend.integration;

import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.UpdateMerchantConfigRequest;
import com.mend.exception.HoldoutControlException;
import com.mend.exception.InvalidRequestException;
import com.mend.experiment.Arm;
import com.mend.experiment.HoldoutAssigner;
import com.mend.provider.PaymentProvider;
import com.mend.scheduler.ActionScheduler;
import com.mend.service.*;
import com.mend.taxonomy.ProviderFailure;
import com.mend.testsupport.TestRows;
import com.mend.testsupport.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * M4 U4.1 (ADR-004 §1-§3, AGENTS.md invariant 22): holdout assignment at first eligibility, on real PostgreSQL. A known
 * salt is inserted for the test merchant before the first assignment, so each test picks payment ids whose bucket puts
 * them in the arm it needs (computed with the production {@link HoldoutAssigner}).
 */
@SpringBootTest
@ActiveProfiles("test")
class HoldoutAssignmentIntegrationTest {

    @Autowired private ClassificationService classificationService;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private RecoveryOrchestratorService orchestrator;
    @Autowired private ActionIntentService actionIntentService;
    @Autowired private HumanApprovalService humanApprovalService;
    @Autowired private MerchantConfigService merchantConfigService;
    @Autowired private ExperimentAssignmentService assignments;
    @Autowired private PaymentReconciliationService reconciliation;
    @Autowired private ActionScheduler actionScheduler;
    @Autowired private MerchantRepository merchants;
    @Autowired private MerchantConfigRepository merchantConfigs;
    @Autowired private CampaignRepository campaigns;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private ActionIntentRepository intents;
    @Autowired private HoldoutAssignmentRepository assignmentRows;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.mend.reply.ReplyPolicy replyPolicy;
    @Autowired private CustomerReplyRepository customerReplies;
    @Autowired private com.mend.controller.CampaignController campaignController;

    @MockitoSpyBean private PaymentProvider provider;

    private static final byte[] SALT = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            SALT[i] = (byte) (31 * i + 7);
        }
    }

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M4 holdout merchant"));
        jdbc.update("INSERT INTO merchant_experiment_salts (merchant_id, purpose, salt_version, salt) VALUES (?, 'ASSIGNMENT', 1, ?)",
                merchantId, SALT);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')", merchantId);
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    private void holdoutRate(int bp) {
        MerchantConfig config = merchantConfigs.findByMerchantId(merchantId).orElseGet(() -> new MerchantConfig(UUID.randomUUID(), merchantId));
        config.setHoldoutRateBp(bp);
        merchantConfigs.save(config);
    }

    /** A fresh payment id whose invoice key ("pay:" + id, no invoice/order on the entity) lands in {@code arm} at {@code rateBp}. */
    private static String paymentIdIn(Arm arm, int rateBp) {
        for (int i = 0; i < 10_000; i++) {
            String payId = "pay_H" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            if (HoldoutAssigner.arm(HoldoutAssigner.bucket(SALT, "pay:" + payId), rateBp) == arm) {
                return payId;
            }
        }
        throw new IllegalStateException("no key found");
    }

    private Campaign failPayment(String payId, String reason) {
        String externalId = "payment.failed_" + payId;
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\",\"error_reason\":\"" + reason + "\"}}}}");
        event.setMerchantId(merchantId);
        webhooks.save(event);
        classificationService.classifyAndPersist(event, ProviderFailure.of(null, reason, "customer"));
        return campaigns.findByMerchantIdAndPaymentId(merchantId, externalId).orElseThrow();
    }

    private List<String> auditTypes(UUID campaignId) {
        return auditLogs.findByCampaignIdOrderByCreatedAtAsc(campaignId).stream().map(AuditLog::getEventType).toList();
    }

    @Test
    @DisplayName("Default rate 0: every campaign is TREATMENT (propensity 1.0) and recovers exactly as before")
    void defaultRateIsTreatmentAndUnchanged() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");

        HoldoutAssignment a = assignmentRows.findById(campaign.getId()).orElseThrow();
        assertEquals(Arm.TREATMENT, a.getArm());
        assertEquals(0, a.getHoldoutRateBp());
        assertEquals(1, a.getSaltVersion());
        assertEquals(HoldoutAssigner.HASH_VERSION, a.getHashVersion());
        assertEquals(HoldoutAssignment.SOURCE_HASH, a.getSource());
        assertEquals(HoldoutAssigner.bucket(SALT, campaign.getInvoiceKey()), a.getBucket());
        assertEquals(CampaignStatus.ACTION_PENDING, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState());
        assertEquals(1, intents.findByCampaignId(campaign.getId()).size());
        assertTrue(auditTypes(campaign.getId()).contains("HOLDOUT_ASSIGNED"));
    }

    @Test
    @DisplayName("CONTROL: no strategy, no intent, no provider call (also after a scheduler pass); the campaign stays ELIGIBLE and tracked")
    void controlTakesNoAction() {
        holdoutRate(5000);
        Campaign campaign = failPayment(paymentIdIn(Arm.CONTROL, 5000), "card_expired");

        assertEquals(Arm.CONTROL, assignmentRows.findById(campaign.getId()).orElseThrow().getArm());
        assertEquals(CampaignStatus.ELIGIBLE, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState());
        assertTrue(intents.findByCampaignId(campaign.getId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recovery_decisions WHERE campaign_id = ?", Integer.class, campaign.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM compliance_decisions WHERE campaign_id = ?", Integer.class, campaign.getId()));
        assertTrue(auditTypes(campaign.getId()).contains("HOLDOUT_CONTROL_NO_ACTION"));

        actionScheduler.pollAndExecuteDueActions();
        assertTrue(intents.findByCampaignId(campaign.getId()).isEmpty());
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("TREATMENT at rate 0.5 behaves like the default (compliance and the intent path unchanged)")
    void treatmentAtNonZeroRateUnchanged() {
        holdoutRate(5000);
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 5000), "card_expired");

        assertEquals(Arm.TREATMENT, assignmentRows.findById(campaign.getId()).orElseThrow().getArm());
        assertEquals(5000, assignmentRows.findById(campaign.getId()).orElseThrow().getHoldoutRateBp());
        assertEquals(1, intents.findByCampaignId(campaign.getId()).size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM compliance_decisions WHERE campaign_id = ?", Integer.class, campaign.getId()));
    }

    @Test
    @DisplayName("CONTROL on every other path: manual orchestration returns nothing, intent creation is refused, the DB trigger rejects a direct insert")
    void controlRefusedOnEveryPath() {
        holdoutRate(5000);
        Campaign campaign = failPayment(paymentIdIn(Arm.CONTROL, 5000), "insufficient_funds");

        assertNull(orchestrator.orchestrateRecovery(merchantId, campaign.getId()));
        assertThrows(HoldoutControlException.class, () -> actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId()));

        DataAccessException rejected = assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO action_intents (id, merchant_id, campaign_id, attempt_number, action_type, status, idempotency_key, scheduled_at, invoice_key) "
                        + "VALUES (?, ?, ?, 1, 'REQUEST_CUSTOMER_ACTION', 'READY', ?, now(), ?)",
                UUID.randomUUID(), merchantId, campaign.getId(), "intent:" + campaign.getId() + ":direct", campaign.getInvoiceKey()));
        assertTrue(rejected.getMostSpecificCause().getMessage().contains("HOLDOUT_CONTROL"), rejected.getMostSpecificCause().getMessage());
        assertTrue(intents.findByCampaignId(campaign.getId()).isEmpty());
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("CONTROL: approving a review is refused (it would resume collection); the review stays PENDING for a human")
    void controlReviewApprovalRefused() {
        holdoutRate(5000);
        Campaign campaign = failPayment(paymentIdIn(Arm.CONTROL, 5000), "card_expired");
        ReviewQueue review = humanApprovalService.createReview(merchantId, campaign.getId(), "CUSTOMER_DISPUTE: customer disputes the charge");

        InvalidRequestException refused = assertThrows(InvalidRequestException.class, () -> humanApprovalService.approveReview(
                merchantId, review.getId(), "approve", TestUsers.withRole(merchantId, "MERCHANT_ADMIN")));
        assertTrue(refused.getMessage().contains("HOLDOUT_CONTROL"));
        assertTrue(intents.findByCampaignId(campaign.getId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM compliance_decisions WHERE campaign_id = ?", Integer.class, campaign.getId()));
        assertEquals(ReviewQueueStatus.PENDING, jdbc.queryForObject("SELECT status FROM review_queue WHERE id = ?", String.class, review.getId())
                .transform(ReviewQueueStatus::valueOf));
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("CONTROL is still tracked: a late capture of the original payment recovers it as ORIGINAL_PAYMENT_LATE_CAPTURE")
    void controlLateCaptureRecorded() {
        holdoutRate(5000);
        String payId = paymentIdIn(Arm.CONTROL, 5000);
        Campaign campaign = failPayment(payId, "card_expired");

        WebhookEvent captured = new WebhookEvent(UUID.randomUUID(), "payment.captured_" + payId, "payment.captured", "RAZORPAY",
                "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"captured\"}}}}");
        captured.setMerchantId(merchantId);
        captured.setProcessingStatus(WebhookEventStatus.VERIFIED);
        reconciliation.reconcileWebhookEvent(webhooks.save(captured));

        Campaign after = campaigns.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, after.getCurrentState());
        assertEquals(RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE, after.getRecoveryAttribution());
        assertEquals(Arm.CONTROL, assignmentRows.findById(campaign.getId()).orElseThrow().getArm());
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("Never re-assigned: a rate change and a second call keep the stored row")
    void neverReassigned() {
        holdoutRate(5000);
        Campaign campaign = failPayment(paymentIdIn(Arm.CONTROL, 5000), "card_expired");
        HoldoutAssignment before = assignmentRows.findById(campaign.getId()).orElseThrow();

        holdoutRate(0);
        HoldoutAssignment again = jdbcTransaction(() -> assignments.assignAtFirstEligibility(campaigns.findById(campaign.getId()).orElseThrow()));

        assertEquals(Arm.CONTROL, again.getArm());
        assertEquals(before.getAssignedAt(), again.getAssignedAt());
        assertEquals(5000, again.getHoldoutRateBp());
        assertEquals(1, auditTypes(campaign.getId()).stream().filter("HOLDOUT_ASSIGNED"::equals).count());
    }

    @Test
    @DisplayName("Re-entering ELIGIBLE (ACTION_PENDING -> ELIGIBLE) does not assign again")
    void reentryKeepsAssignment() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");
        assertEquals(CampaignStatus.ACTION_PENDING, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState());
        Instant assignedAt = assignmentRows.findById(campaign.getId()).orElseThrow().getAssignedAt();
        holdoutRate(5000);

        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "test re-entry", "SYSTEM", null);

        assertEquals(assignedAt, assignmentRows.findById(campaign.getId()).orElseThrow().getAssignedAt());
        assertEquals(Arm.TREATMENT, assignmentRows.findById(campaign.getId()).orElseThrow().getArm());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM holdout_assignments WHERE campaign_id = ?", Integer.class, campaign.getId()));
    }

    @Test
    @DisplayName("One arm per invoice: a second campaign of the same invoice copies the stored arm, even after a rate change (0.5 -> 0.1)")
    void secondCampaignOfInvoiceReusesArm() {
        holdoutRate(5000);
        String invoiceId = null;
        for (int i = 0; i < 10_000 && invoiceId == null; i++) {
            String candidate = "inv_H" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            int bucket = HoldoutAssigner.bucket(SALT, "inv:" + candidate);
            // CONTROL at 0.5, but TREATMENT if it were re-hashed at 0.1
            if (bucket >= 1000 && bucket < 5000) {
                invoiceId = candidate;
            }
        }
        Campaign first = failInvoicePayment(invoiceId);
        holdoutRate(1000);
        Campaign second = failInvoicePayment(invoiceId);

        assertEquals(first.getInvoiceKey(), second.getInvoiceKey());
        HoldoutAssignment reused = assignmentRows.findById(second.getId()).orElseThrow();
        assertEquals(Arm.CONTROL, reused.getArm());
        assertEquals(HoldoutAssignment.SOURCE_INVOICE_REUSE, reused.getSource());
        assertEquals(5000, reused.getHoldoutRateBp());
        assertTrue(intents.findByCampaignId(second.getId()).isEmpty());
    }

    private Campaign failInvoicePayment(String invoiceId) {
        String payId = "pay_I" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String externalId = "payment.failed_" + payId;
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId + "\",\"invoice_id\":\"" + invoiceId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\",\"error_reason\":\"card_expired\"}}}}");
        event.setMerchantId(merchantId);
        webhooks.save(event);
        classificationService.classifyAndPersist(event, ProviderFailure.of(null, "card_expired", "customer"));
        return campaigns.findByMerchantIdAndPaymentId(merchantId, externalId).orElseThrow();
    }

    @Test
    @DisplayName("Assignments and salts are immutable: UPDATE and DELETE are rejected by the database; the statement-level (table-wide) guard is installed")
    void immutable() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");

        for (String sql : List.of(
                "UPDATE holdout_assignments SET arm = 'CONTROL', bucket = 0, holdout_rate_bp = 100 WHERE campaign_id = '" + campaign.getId() + "'",
                "DELETE FROM holdout_assignments WHERE campaign_id = '" + campaign.getId() + "'",
                "UPDATE merchant_experiment_salts SET salt_version = 2 WHERE merchant_id = '" + merchantId + "'",
                "DELETE FROM merchant_experiment_salts WHERE merchant_id = '" + merchantId + "'")) {
            DataAccessException e = assertThrows(DataAccessException.class, () -> jdbc.execute(sql), sql);
            assertTrue(e.getMostSpecificCause().getMessage().contains("APPEND_ONLY"), sql + " -> " + e.getMostSpecificCause().getMessage());
        }
        assertEquals(Arm.TREATMENT, assignmentRows.findById(campaign.getId()).orElseThrow().getArm());
        // Executing a table-wide statement would endanger the shared test database if the guard were missing (TestHygieneTest),
        // so the statement-level guard is checked in the catalog: tgtype bit 5 (32) = TRUNCATE, bit 1 (2) = BEFORE.
        for (String table : List.of("holdout_assignments", "merchant_experiment_salts")) {
            Integer guards = jdbc.queryForObject("SELECT count(*) FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid "
                    + "WHERE t.tgrelid = ?::regclass AND (t.tgtype & 32) <> 0 AND (t.tgtype & 2) <> 0 AND p.proname = 'mend_reject_modification'",
                    Integer.class, table);
            assertEquals(1, guards, table);
        }
    }

    @Test
    @DisplayName("Merchant config: holdoutRateBp 0..5000 accepted and audited, out of range refused, omitted keeps the rate")
    void holdoutRateConfig() {
        var admin = TestUsers.withRole(merchantId, "MERCHANT_ADMIN");
        UpdateMerchantConfigRequest request = new UpdateMerchantConfigRequest(3, 24, "EXPONENTIAL_BACKOFF");
        request.setHoldoutRateBp(2000);
        assertEquals(2000, merchantConfigService.updateMerchantConfig(merchantId, request, admin).getHoldoutRateBp());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE merchant_id = ? AND event_type = 'HOLDOUT_RATE_CHANGED'",
                Integer.class, merchantId));

        request.setHoldoutRateBp(5001);
        assertThrows(InvalidRequestException.class, () -> merchantConfigService.updateMerchantConfig(merchantId, request, admin));
        request.setHoldoutRateBp(-1);
        assertThrows(InvalidRequestException.class, () -> merchantConfigService.updateMerchantConfig(merchantId, request, admin));

        request.setHoldoutRateBp(null);
        assertEquals(2000, merchantConfigService.updateMerchantConfig(merchantId, request, admin).getHoldoutRateBp());
        assertEquals(2000, merchantConfigService.getMerchantConfig(merchantId, admin).getHoldoutRateBp());
    }

    @Test
    @DisplayName("CONTROL keeps customer requests: an OPT_OUT reply still opts out, a DISPUTE reply still opens a review")
    void controlKeepsCustomerRequests() {
        holdoutRate(5000);
        Campaign campaign = failPayment(paymentIdIn(Arm.CONTROL, 5000), "card_expired");

        CustomerReply stop = customerReplies.save(new CustomerReply(UUID.randomUUID(), merchantId, campaign.getId(), "please stop", null));
        String effect = jdbcTransaction(() -> replyPolicy.apply(stop,
                ReplySuggestion.valid(stop.getId(), merchantId, ReplyIntent.OPT_OUT, null, false, "test-model", "test-prompt")));
        assertEquals(com.mend.reply.ReplyPolicy.CONTACT_OPTED_OUT, effect);
        assertNotNull(jdbc.queryForObject("SELECT contact_opted_out_at FROM campaigns WHERE id = ?", java.sql.Timestamp.class, campaign.getId()));

        CustomerReply dispute = customerReplies.save(new CustomerReply(UUID.randomUUID(), merchantId, campaign.getId(), "not mine", null));
        assertEquals(com.mend.reply.ReplyPolicy.REVIEW_CUSTOMER_DISPUTE, jdbcTransaction(() -> replyPolicy.apply(dispute,
                ReplySuggestion.valid(dispute.getId(), merchantId, ReplyIntent.DISPUTE, null, false, "test-model", "test-prompt"))));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM review_queue WHERE campaign_id = ? AND status = 'PENDING'",
                Integer.class, campaign.getId()));
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("Manual start-recovery on a CONTROL campaign: no intent, no provider call, an honest message")
    void controlManualStart() {
        holdoutRate(5000);
        Campaign campaign = failPayment(paymentIdIn(Arm.CONTROL, 5000), "card_expired");
        var response = campaignController.startRecovery(new com.mend.dto.StartRecoveryRequest(campaign.getPaymentId(), null, null),
                merchantId.toString(), TestUsers.withRole(merchantId, "MERCHANT_ADMIN")).getBody();
        assertNotNull(response);
        assertNull(response.getActionIntent());
        assertTrue(response.getMessage().startsWith("Holdout CONTROL"), response.getMessage());
        assertTrue(intents.findByCampaignId(campaign.getId()).isEmpty());
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("Concurrent first eligibility of two campaigns of one invoice: one HASH, one INVOICE_REUSE, the same arm (real threads)")
    void concurrentCampaignsOfOneInvoice() throws Exception {
        holdoutRate(2000);
        String invoiceId = "inv_C" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<Campaign> classified = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            classified.add(classifiedCampaignOfInvoice(invoiceId));
        }
        runConcurrently(classified);

        List<HoldoutAssignment> rows = classified.stream().map(c -> assignmentRows.findById(c.getId()).orElseThrow()).toList();
        assertEquals(rows.get(0).getArm(), rows.get(1).getArm());
        assertEquals(java.util.Set.of(HoldoutAssignment.SOURCE_HASH, HoldoutAssignment.SOURCE_INVOICE_REUSE),
                java.util.Set.of(rows.get(0).getSource(), rows.get(1).getSource()));
    }

    @Test
    @DisplayName("Concurrent first assignments for a merchant without a salt: one salt row, both assignments use it (real threads)")
    void concurrentSaltCreation() throws Exception {
        UUID saltless = UUID.randomUUID();
        merchants.save(new Merchant(saltless, "M4 saltless merchant"));
        UUID previous = merchantId;
        merchantId = saltless;
        try {
            List<Campaign> classified = List.of(
                    classifiedCampaignOfInvoice("inv_S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)),
                    classifiedCampaignOfInvoice("inv_S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)));
            runConcurrently(classified);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM merchant_experiment_salts WHERE merchant_id = ?", Integer.class, saltless));
            for (Campaign c : classified) {
                assertEquals(1, assignmentRows.findById(c.getId()).orElseThrow().getSaltVersion());
            }
        } finally {
            merchantId = previous;
            TestRows.deleteMerchant(jdbc, saltless);
        }
    }

    @Test
    @DisplayName("DECISION U4.1-e: with the holdout turned off (rate 0) a new campaign of a former CONTROL invoice is TREATMENT")
    void holdoutOffEndsTheExperimentForNewCampaigns() {
        holdoutRate(5000);
        String invoiceId = null;
        for (int i = 0; i < 10_000 && invoiceId == null; i++) {
            String candidate = "inv_O" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (HoldoutAssigner.arm(HoldoutAssigner.bucket(SALT, "inv:" + candidate), 5000) == Arm.CONTROL) {
                invoiceId = candidate;
            }
        }
        Campaign first = failInvoicePayment(invoiceId);
        assertEquals(Arm.CONTROL, assignmentRows.findById(first.getId()).orElseThrow().getArm());
        holdoutRate(0);
        Campaign second = failInvoicePayment(invoiceId);
        HoldoutAssignment after = assignmentRows.findById(second.getId()).orElseThrow();
        assertEquals(Arm.TREATMENT, after.getArm());
        assertEquals(HoldoutAssignment.SOURCE_HASH, after.getSource());
        assertEquals(Arm.CONTROL, assignmentRows.findById(first.getId()).orElseThrow().getArm());
    }

    /** A CLASSIFIED campaign whose failure webhook names {@code invoiceId} (so its invoice key is "inv:" + id). */
    private Campaign classifiedCampaignOfInvoice(String invoiceId) {
        String payId = "pay_C" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String externalId = "payment.failed_" + payId;
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId + "\",\"invoice_id\":\"" + invoiceId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\"}}}}");
        event.setMerchantId(merchantId);
        webhooks.save(event);
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, externalId, null, null);
        return campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "test", "SYSTEM", null);
    }

    /** Both campaigns go CLASSIFIED -> ELIGIBLE at the same moment, each in its own transaction on its own thread. */
    private void runConcurrently(List<Campaign> classified) throws Exception {
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(classified.size());
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (Campaign c : classified) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return campaignLifecycleService.transitionState(c.getMerchantId(), c.getId(), CampaignStatus.ELIGIBLE, "test", "SYSTEM", null);
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private <T> T jdbcTransaction(java.util.function.Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }
}
