package com.mend.integration.m2;

import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RejectionKind;
import com.mend.provider.RequestResult;
import com.mend.provider.mock.MockPaymentProvider;
import com.mend.scheduler.ActionScheduler;
import com.mend.security.AuthenticatedUser;
import com.mend.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 U9: MEND-side errors defer (never EXHAUSTED; the cap leads to an operator review) and MANUAL_REVIEW goes to the review
 * queue (no intent until a human approves; approval issues a payment link, OD-1). Real PostgreSQL; time travel by SQL.
 * Shares ExecutionSafetyScenarioTest's context (same @Import and @MockitoBean).
 */
@Import(BlockingProviderConfig.class)
@DisplayName("M2 U9 deferral and manual review")
class DeferralAndManualReviewTest extends AbstractIntegrationTest {

    @Autowired private ActionIntentClaimService claimService;
    @Autowired private ActionExecutionService executor;
    @Autowired private ActionIntentService actionIntentService;
    @Autowired private HumanApprovalService humanApprovalService;
    @Autowired private RecoveryStrategyService recoveryStrategyService;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private ActionIntentRepository intents;
    @Autowired private CampaignRepository campaigns;
    @Autowired private MerchantRepository merchants;
    @Autowired private MerchantConfigRepository merchantConfigs;
    @Autowired private ComplianceDecisionRepository complianceDecisions;
    @Autowired private ClassificationResultRepository classifications;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private ReviewQueueRepository reviews;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider mock;
    @Autowired private BlockingPaymentProvider provider;
    @Value("${mend.execution.max-deferrals:5}") private int maxDeferrals;

    @MockitoBean private RecoveryOrchestratorService recoveryOrchestratorService;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        provider.reset();
        mock.reset();
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M2 U9 merchant"));
        parkOtherRows();
    }

    @AfterEach
    void tearDown() {
        provider.reset();
        mock.reset();
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')", merchantId);
        jdbc.update("UPDATE campaigns SET current_state = 'CANCELLED' WHERE merchant_id = ? "
                + "AND current_state NOT IN ('RECOVERED','EXHAUSTED','FAILED','CANCELLED')", merchantId);
        jdbc.update("UPDATE review_queue SET status = 'CLOSED' WHERE merchant_id = ? AND status = 'PENDING'", merchantId);
        jdbc.update("UPDATE webhook_events SET processing_status = 'PROCESSED' WHERE merchant_id = ? AND processing_status = 'VERIFIED'", merchantId);
    }

    private void parkOtherRows() {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING') AND merchant_id <> ?", merchantId);
    }

    // ---- fixtures ----

    /** ACTION_PENDING campaign with a READY intent; {@code withAmount=false} leaves the original amount unresolvable. */
    private ActionIntent readyIntent(ActionType type, boolean withAmount) {
        String paymentId = "pay_m2u9" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String amount = withAmount ? ",\"amount\":49900,\"currency\":\"INR\"" : "";
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"status\":\"failed\"" + amount + "}}}}");
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhooks.save(event);
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, null, null);
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaigns.save(campaign);
        ComplianceDecisionEntity compliance = complianceDecisions.save(new ComplianceDecisionEntity(UUID.randomUUID(), campaign.getId(),
                merchantId, null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED, ComplianceReason.ALLOWED,
                "Compliant action", com.mend.testsupport.TestCompliance.CURRENT_POLICY));
        ActionIntent intent = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, type.name(),
                RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), compliance.getId(), ActionIntentStatus.READY,
                "intent:" + campaign.getId() + ":attempt_1:" + type.name(), Instant.now().minusSeconds(5));
        intent.setInvoiceKey(campaign.getInvoiceKey());
        return intents.save(intent);
    }

    private ActionIntent reload(ActionIntent intent) {
        return intents.findById(intent.getId()).orElseThrow();
    }

    private CampaignStatus campaignState(ActionIntent intent) {
        return campaigns.findById(intent.getCampaignId()).orElseThrow().getCurrentState();
    }

    private List<String> auditTypes(ActionIntent intent) {
        return auditLogs.findByCampaignIdOrderByCreatedAtAsc(intent.getCampaignId()).stream().map(AuditLog::getEventType).toList();
    }

    private void poll() {
        new ActionScheduler(claimService, executor, "worker-u9", 50, true, true).pollAndExecuteDueActions();
    }

    // ---- DEFER: one test per code ----

    enum DeferCode { NOT_SENT, RATE_LIMITED, NO_CREDENTIALS_FOR_MERCHANT, AMOUNT_UNRESOLVED }

    private ActionIntent arrange(DeferCode code) {
        ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION, code != DeferCode.AMOUNT_UNRESOLVED);
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        switch (code) {
            case NOT_SENT -> mock.setSimulatedNotSent(true);
            case RATE_LIMITED -> provider.answerCreate(key, new RequestResult.Rejected<>(RejectionKind.RATE_LIMITED, null, null, 429));
            case NO_CREDENTIALS_FOR_MERCHANT -> provider.answerCreate(key,
                    new RequestResult.Rejected<>(RejectionKind.AUTHENTICATION, "NO_CREDENTIALS_FOR_MERCHANT", null, null));
            case AMOUNT_UNRESOLVED -> { }
        }
        return intent;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DeferCode.class)
    @DisplayName("DEFER code: READY later + campaign ACTION_PENDING each time; never EXHAUSTED; after the cap FAILED + ELIGIBLE + operator review")
    void deferCodeNeverExhausts(DeferCode code) {
        ActionIntent intent = arrange(code);
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());

        poll();
        ActionIntent deferred = reload(intent);
        assertEquals(ActionIntentStatus.READY, deferred.getStatus(), "first failure is deferred");
        assertTrue(deferred.getScheduledAt().isAfter(Instant.now().plusSeconds(20)), "rescheduled at least the base delay ahead (backoff floor)");
        assertNull(deferred.getClaimToken(), "ownership cleared");
        assertEquals(CampaignStatus.ACTION_PENDING, campaignState(intent));
        assertTrue(auditTypes(intent).contains("EXECUTION_DEFERRED"));

        int dispatches = 1;
        while (reload(intent).getStatus() == ActionIntentStatus.READY && dispatches < maxDeferrals + 3) {
            jdbc.update("UPDATE action_intents SET scheduled_at = now() - interval '1 second' WHERE id = ?", intent.getId());
            poll();
            dispatches++;
            assertNotEquals(CampaignStatus.EXHAUSTED, campaignState(intent), "a DEFER code never exhausts (" + code + ")");
        }

        assertEquals(maxDeferrals + 1, dispatches, "deferred " + maxDeferrals + " times, then the cap");
        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.ELIGIBLE, campaignState(intent));
        ReviewQueue review = reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(intent.getCampaignId(), ReviewQueueStatus.PENDING).orElseThrow();
        assertTrue(review.getReason().startsWith("OPERATOR_ACTION_REQUIRED:"), review.getReason());
        assertEquals(maxDeferrals, auditTypes(intent).stream().filter("EXECUTION_DEFERRED"::equals).count());
        int expectedCalls = code == DeferCode.AMOUNT_UNRESOLVED ? 0 : maxDeferrals + 1;
        assertEquals(expectedCalls, provider.createCalls(key), "same intent and attempt key every time; no call without an amount");
    }

    // ---- TERMINAL stays terminal; REVIEW is not exhausted ----

    @Test
    @DisplayName("Provider VALIDATION rejection is TERMINAL: FAILED + EXHAUSTED, no deferral")
    void validationRejectionIsTerminal() {
        ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION, true);
        mock.setSimulatedStatus(PaymentExecutionStatus.FAILURE);

        poll();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.EXHAUSTED, campaignState(intent));
        assertFalse(auditTypes(intent).contains("EXECUTION_DEFERRED"));
    }

    @Test
    @DisplayName("A legacy MANUAL_REVIEW intent reaching the executor: no provider call, FAILED + ELIGIBLE + review, not EXHAUSTED")
    void legacyManualReviewIntentGoesToReview() {
        ActionIntent intent = readyIntent(ActionType.MANUAL_REVIEW, true);

        poll();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.ELIGIBLE, campaignState(intent));
        assertEquals(0, provider.debitCalls(ProviderAttemptKey.forIntent(intent.getId())), "manual review never reaches the provider, even in mock mode");
        assertTrue(mock.getRecordedRequests().isEmpty());
        assertTrue(reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(intent.getCampaignId(), ReviewQueueStatus.PENDING)
                .orElseThrow().getReason().startsWith("HUMAN_DECISION_REQUIRED:MANUAL_REVIEW_NOT_AUTOMATED"));
    }

    @Test
    @DisplayName("Classifier: every outcome has one class; Unknown is never DEFER (only reconciliation resolves it)")
    void classification() {
        assertEquals(OutcomeRetryPolicy.DEFER, OutcomeRetryPolicy.classify(new ProviderActionOutcome.NotSent("X")));
        assertEquals(OutcomeRetryPolicy.DEFER, OutcomeRetryPolicy.classify(new ProviderActionOutcome.Rejected(RejectionKind.RATE_LIMITED, null, null, 429)));
        assertEquals(OutcomeRetryPolicy.DEFER, OutcomeRetryPolicy.classify(new ProviderActionOutcome.Rejected(RejectionKind.AUTHENTICATION, "NO_CREDENTIALS_FOR_MERCHANT", null, null)));
        assertEquals(OutcomeRetryPolicy.DEFER, OutcomeRetryPolicy.classify(new ProviderActionOutcome.Rejected(RejectionKind.LOCAL_PRECONDITION, "AMOUNT_UNRESOLVED", null, null)));
        assertEquals(OutcomeRetryPolicy.TERMINAL, OutcomeRetryPolicy.classify(new ProviderActionOutcome.Rejected(RejectionKind.AUTHENTICATION, "BAD_REQUEST_ERROR", null, 401)));
        assertEquals(OutcomeRetryPolicy.TERMINAL, OutcomeRetryPolicy.classify(new ProviderActionOutcome.Rejected(RejectionKind.LOCAL_PRECONDITION, "AMOUNT_BELOW_MINIMUM", null, null)));
        assertEquals(OutcomeRetryPolicy.TERMINAL, OutcomeRetryPolicy.classify(new ProviderActionOutcome.Rejected(RejectionKind.VALIDATION, "X", null, 400)));
        assertEquals(OutcomeRetryPolicy.TERMINAL, OutcomeRetryPolicy.classify(new ProviderActionOutcome.Rejected(RejectionKind.DUPLICATE_REFERENCE, "X", null, 400)));
        assertEquals(OutcomeRetryPolicy.REVIEW, OutcomeRetryPolicy.classify(new ProviderActionOutcome.NotSupported("RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2")));
        assertEquals(OutcomeRetryPolicy.REVIEW, OutcomeRetryPolicy.classify(new ProviderActionOutcome.NotSupported("MANUAL_REVIEW_NOT_AUTOMATED")));
        assertEquals(OutcomeRetryPolicy.TERMINAL, OutcomeRetryPolicy.classify(new ProviderActionOutcome.NotSupported("UNSUPPORTED_ACTION_TYPE")));
        assertEquals(OutcomeRetryPolicy.NONE, OutcomeRetryPolicy.classify(new ProviderActionOutcome.Unknown("TIMEOUT", null)));
    }

    // ---- MANUAL_REVIEW: zero intents until approved; approval yields a link intent ----

    @Test
    @DisplayName("MANUAL_REVIEW creates a review and zero intents; approval (OD-1) re-runs compliance and yields a REQUEST_CUSTOMER_ACTION intent")
    void manualReviewThenApprovalYieldsLink() {
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "pay_m2u9mr" + UUID.randomUUID().toString().substring(0, 8), null, null);
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);
        // M3 behaviour change (ADR-003 §4): an unrecognised class is now UNCLASSIFIED and fails closed, so it can no longer
        // reach approval. MANUAL_REVIEW comes from the merchant's enabled actions instead: INSUFFICIENT_FUNDS computes
        // RETRY_LATER, which this merchant has not enabled (was: class "SUSPECTED_DISPUTE" with confidence 0.95).
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setEnabledRecoveryActions("CUSTOMER_ACTION_REQUIRED");
        merchantConfigs.save(config);
        classifications.save(com.mend.testsupport.TestClassifications.deterministic(new ClassificationResult(UUID.randomUUID(), null, campaign.getId(), "INSUFFICIENT_FUNDS",
                null, null, "Razorpay error_reason 'insufficient_funds'", null)));
        assertEquals(RecoveryStrategy.MANUAL_REVIEW, recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId()).getStrategy());

        assertNull(actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId()));
        assertTrue(intents.findByCampaignId(campaign.getId()).isEmpty(), "zero intents until approved");
        ReviewQueue review = reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(campaign.getId(), ReviewQueueStatus.PENDING).orElseThrow();
        assertTrue(review.getReason().startsWith("MANUAL_REVIEW_REQUIRED"));

        UUID reviewerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, 'x', 'Reviewer')",
                reviewerId, "reviewer-" + reviewerId + "@test.invalid");
        AuthenticatedUser reviewer = new AuthenticatedUser(reviewerId, "reviewer@test.invalid", "Reviewer", "ACTIVE",
                List.of(new AuthenticatedUser.MerchantMembershipInfo(merchantId, "M2 U9 merchant", UUID.randomUUID(), "REVIEWER")));
        humanApprovalService.approveReview(merchantId, review.getId(), "approved", reviewer);

        List<ActionIntent> created = intents.findByCampaignId(campaign.getId());
        assertEquals(1, created.size());
        assertEquals(ActionType.REQUEST_CUSTOMER_ACTION.name(), created.get(0).getActionType(), "OD-1: approval issues a payment link");
        ComplianceDecisionEntity compliance = complianceDecisions.findById(created.get(0).getComplianceDecisionId()).orElseThrow();
        assertEquals(ComplianceStatus.COMPLIANCE_ALLOWED, compliance.getStatus(), "compliance was re-run for the approved action");
        assertEquals(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, compliance.getStrategy());
        assertEquals(CampaignStatus.ACTION_PENDING, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState());
    }

    // ---- U9 review fixes ----

    private AuthenticatedUser reviewer() {
        UUID reviewerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, display_name) VALUES (?, ?, 'x', 'Reviewer')",
                reviewerId, "reviewer-" + reviewerId + "@test.invalid");
        return new AuthenticatedUser(reviewerId, "reviewer@test.invalid", "Reviewer", "ACTIVE",
                List.of(new AuthenticatedUser.MerchantMembershipInfo(merchantId, "M2 U9 merchant", UUID.randomUUID(), "REVIEWER")));
    }

    @Test
    @DisplayName("Approving the operator review after the deferral cap runs a NEW attempt (not the ended intent) and it executes")
    void approvalAfterDeferralCapRunsANewAttempt() {
        ActionIntent intent = arrange(DeferCode.AMOUNT_UNRESOLVED);
        for (int i = 0; i <= maxDeferrals && reload(intent).getStatus() != ActionIntentStatus.FAILED; i++) {
            jdbc.update("UPDATE action_intents SET scheduled_at = now() - interval '1 second' WHERE id = ?", intent.getId());
            poll();
        }
        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.ELIGIBLE, campaignState(intent));
        // The operator fixed the cause (the amount is now known) and the campaign has a normal recovery decision.
        Campaign campaign = campaigns.findById(intent.getCampaignId()).orElseThrow();
        webhooks.findByMerchantIdAndExternalEventId(merchantId, campaign.getPaymentId()).ifPresent(event -> {
            event.setRawPayload("{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + campaign.getPaymentId()
                    + "\",\"status\":\"failed\",\"amount\":49900,\"currency\":\"INR\"}}}}");
            webhooks.save(event);
        });
        // M3 behaviour change: the pre-M3 class CARD_EXPIRED fails closed; its taxonomy equivalent is INSTRUMENT_INVALID.
        classifications.save(com.mend.testsupport.TestClassifications.deterministic(new ClassificationResult(UUID.randomUUID(), null, campaign.getId(), "INSTRUMENT_INVALID",
                null, null, "Razorpay error_reason 'card_expired'", null)));
        recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());
        ReviewQueue review = reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(campaign.getId(), ReviewQueueStatus.PENDING).orElseThrow();

        humanApprovalService.approveReview(merchantId, review.getId(), "fixed", reviewer());

        List<ActionIntent> all = intents.findByCampaignId(campaign.getId());
        ActionIntent next = all.stream().filter(i -> !i.getId().equals(intent.getId())).findFirst().orElseThrow(
                () -> new AssertionError("approval must create a new attempt, intents: " + all.size()));
        assertEquals(2, next.getAttemptNumber());
        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus(), "the ended attempt is left as it was");
        jdbc.update("UPDATE action_intents SET scheduled_at = now() - interval '1 second' WHERE id = ?", next.getId());
        poll();
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, intents.findById(next.getId()).orElseThrow().getStatus(), "the approved attempt really runs");
    }

    @Test
    @DisplayName("The OD-1 approval decision is never reused by a path without an approval (invariant 7)")
    void approvalDecisionIsNotReusedWithoutApproval() {
        manualReviewThenApprovalYieldsLink();
        Campaign campaign = campaigns.findAll().stream().filter(c -> merchantId.equals(c.getMerchantId())).findFirst().orElseThrow();
        // Back to ELIGIBLE with nothing live (as after a cancelled attempt), then a plain, unapproved creation:
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE campaign_id = ?", campaign.getId());
        jdbc.update("UPDATE campaigns SET current_state = 'ELIGIBLE' WHERE id = ?", campaign.getId());
        jdbc.update("UPDATE review_queue SET status = 'CLOSED' WHERE campaign_id = ? AND status = 'PENDING'", campaign.getId());

        assertNull(actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId()),
                "MANUAL_REVIEW is re-evaluated: no intent without a new human approval");
        assertTrue(reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(campaign.getId(), ReviewQueueStatus.PENDING).isPresent());
    }

    @Test
    @DisplayName("A DEFER outcome when the campaign is no longer EXECUTING is routed to review (no orphan READY intent)")
    void deferWithoutExecutingCampaignGoesToReview() throws Exception {
        ActionIntent intent = readyIntent(ActionType.REQUEST_CUSTOMER_ACTION, true);
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        provider.answerCreate(key, new RequestResult.Rejected<>(RejectionKind.RATE_LIMITED, null, null, 429));
        provider.blockFirstCreate(key);
        java.util.concurrent.ExecutorService thread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> worker = thread.submit(this::poll);
            assertTrue(provider.awaitEntered(key));
            jdbc.update("UPDATE campaigns SET current_state = 'ELIGIBLE' WHERE id = ?", intent.getCampaignId());   // moved meanwhile
            provider.release(key);
            worker.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            provider.release(key);
            thread.shutdownNow();
        }
        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus(), "not left READY where no scheduler would claim it");
        assertEquals(CampaignStatus.ELIGIBLE, campaignState(intent));
        assertTrue(reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(intent.getCampaignId(), ReviewQueueStatus.PENDING)
                .orElseThrow().getReason().startsWith("OPERATOR_ACTION_REQUIRED:"));
    }

    @Test
    @DisplayName("429 on an action the provider does not dedup (debit): routed to review, never resent automatically (invariant 5)")
    void rateLimitedNonResendSafeActionIsNotDeferred() {
        ActionIntent intent = readyIntent(ActionType.RETRY_PAYMENT, true);
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        provider.answerDebit(key, new RequestResult.Rejected<>(RejectionKind.RATE_LIMITED, null, null, 429));

        poll();

        assertEquals(ActionIntentStatus.FAILED, reload(intent).getStatus());
        assertEquals(CampaignStatus.ELIGIBLE, campaignState(intent));
        assertEquals(1, provider.debitCalls(key));
        assertFalse(auditTypes(intent).contains("EXECUTION_DEFERRED"));
        assertTrue(reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(intent.getCampaignId(), ReviewQueueStatus.PENDING)
                .orElseThrow().getReason().startsWith("HUMAN_DECISION_REQUIRED:"));
    }
}
