package com.mend.integration.m2;

import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.integration.AbstractIntegrationTest;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 U8: campaign-level events (the original payment, the subscription) never finalize a recovery intent (invariant 15);
 * a late capture of the original payment closes the campaign with non-MEND attribution. Real PostgreSQL; shares
 * ExecutionSafetyScenarioTest's context (same @Import and @MockitoBean).
 */
@Import(BlockingProviderConfig.class)
@DisplayName("M2 U8 correlation")
class CorrelationTest extends AbstractIntegrationTest {

    @Autowired private PaymentReconciliationService reconciliation;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private InvoiceKeyResolver invoiceKeyResolver;
    @Autowired private ActionIntentClaimService claimService;
    @Autowired private ActionExecutionService executor;
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
    @Autowired private EventProcessingService eventProcessing;

    @MockitoBean private RecoveryOrchestratorService recoveryOrchestratorService;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        provider.reset();
        mock.reset();
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M2 U8 merchant"));
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING') AND merchant_id <> ?", merchantId);
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

    // ---- fixtures ----

    private static String randomId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private WebhookEvent webhook(String externalEventId, String eventType, String payload) {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalEventId, eventType, "RAZORPAY", payload);
        event.setMerchantId(merchantId);
        event.setProcessingStatus(WebhookEventStatus.VERIFIED);
        return webhooks.save(event);
    }

    private static String paymentPayload(String eventType, String paymentId, String status, String extraEntityFields) {
        return "{\"event\":\"" + eventType + "\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"" + status + "\"" + extraEntityFields + "}}}}";
    }

    /** Webhook-created campaign: payment_id is the composite "payment.failed_<pay id>", as the production path stores it. */
    private Campaign webhookCampaign(String payId, CampaignStatus state) {
        String externalEventId = "payment.failed_" + payId;
        webhook(externalEventId, "payment.failed", paymentPayload("payment.failed", payId, "failed", ""));
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, externalEventId, null, null);
        campaign.setCurrentState(state);
        return campaigns.save(campaign);
    }

    private ActionIntent intent(Campaign campaign, ActionType type, ActionIntentStatus status, String responseReference) {
        ComplianceDecisionEntity compliance = complianceDecisions.save(new ComplianceDecisionEntity(UUID.randomUUID(), campaign.getId(),
                merchantId, null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED, ComplianceReason.ALLOWED,
                // M3 behaviour change: was a v1.x version, which is now LEGACY_DECISION at dispatch (see TestCompliance)
                "Compliant action", com.mend.testsupport.TestCompliance.CURRENT_POLICY));
        ActionIntent intent = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, type.name(),
                RecoveryStrategy.CUSTOMER_ACTION_REQUIRED.name(), compliance.getId(), status,
                "intent:" + campaign.getId() + ":attempt_1:" + type.name(), Instant.now().minusSeconds(5));
        intent.setInvoiceKey(campaign.getInvoiceKey());
        intent.setResponseReference(responseReference);
        return intents.save(intent);
    }

    private ActionIntentStatus status(ActionIntent intent) {
        return intents.findById(intent.getId()).orElseThrow().getStatus();
    }

    private Campaign reload(Campaign campaign) {
        return campaigns.findById(campaign.getId()).orElseThrow();
    }

    private List<String> auditTypes(Campaign campaign) {
        return auditLogs.findByCampaignIdOrderByCreatedAtAsc(campaign.getId()).stream().map(AuditLog::getEventType).toList();
    }

    private java.util.Optional<ReviewQueue> pendingReview(Campaign campaign) {
        return reviews.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(campaign.getId(), ReviewQueueStatus.PENDING);
    }

    private ReconciliationResult capturedOriginal(String payId) {
        return reconciliation.reconcileWebhookEvent(webhook("payment.captured_" + payId, "payment.captured",
                paymentPayload("payment.captured", payId, "captured", "")));
    }

    // ---- provider_payment_id at creation ----

    @Test
    @DisplayName("provider_payment_id: the real pay_ id from the failure webhook, the raw id for manual campaigns, null when unknown")
    void providerPaymentIdIsTheRealPaymentIdOrNull() {
        String payId = randomId("pay_U8a");
        assertEquals(payId, webhookCampaign(payId, CampaignStatus.CREATED).getProviderPaymentId());
        assertEquals("payment.failed_" + payId, campaigns.findAllByMerchantIdAndProviderPaymentId(merchantId, payId).get(0).getPaymentId(),
                "payment_id keeps its composite meaning");

        String raw = randomId("pay_U8b");
        assertEquals(raw, campaignLifecycleService.getOrCreateCampaign(merchantId, raw, null, null).getProviderPaymentId());

        assertNull(campaignLifecycleService.getOrCreateCampaign(merchantId, randomId("evt_U8c"), null, null).getProviderPaymentId(),
                "no webhook and not a pay_ id: unknown");

        String badEvent = "payment.failed_" + randomId("x");
        webhook(badEvent, "payment.failed", paymentPayload("payment.failed", "pay_bad id; --", "failed", ""));
        assertNull(campaignLifecycleService.getOrCreateCampaign(merchantId, badEvent, null, null).getProviderPaymentId(),
                "malformed payment id in the untrusted payload: not trusted");
        assertNull(invoiceKeyResolver.resolveProviderPaymentId(null, "payment.failed_pay_x"));
    }

    // ---- original payment failure: no-op ----

    @Test
    @DisplayName("A redelivered failure of the ORIGINAL payment changes no intent and no campaign (link and non-link intents)")
    void originalPaymentFailureIsANoOp() {
        String payId = randomId("pay_U8f");
        Campaign campaign = webhookCampaign(payId, CampaignStatus.EXECUTING);
        ActionIntent link = intent(campaign, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.AWAITING_PAYMENT, "plink_" + randomId("U8f"));

        ReconciliationResult result = reconciliation.reconcileWebhookEvent(webhook("payment.failed_" + payId + "_again", "payment.failed",
                paymentPayload("payment.failed", payId, "failed", "")));

        assertEquals(ReconciliationResult.Status.SKIPPED_CAMPAIGN_LEVEL, result.getStatus());
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, status(link));
        assertEquals(CampaignStatus.EXECUTING, reload(campaign).getCurrentState());

        String debitPay = randomId("pay_U8g");
        Campaign other = webhookCampaign(debitPay, CampaignStatus.EXECUTING);
        ActionIntent debit = intent(other, ActionType.RETRY_PAYMENT, ActionIntentStatus.NEEDS_RECONCILIATION, null);
        reconciliation.reconcileWebhookEvent(webhook("payment.failed_" + debitPay + "_again", "payment.failed",
                paymentPayload("payment.failed", debitPay, "failed", "")));
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, status(debit));
        assertEquals(CampaignStatus.EXECUTING, reload(other).getCurrentState());
    }

    // ---- late capture ----

    @Test
    @DisplayName("Late capture with a live link: campaign RECOVERED (ORIGINAL_PAYMENT_LATE_CAPTURE), link intent CANCELLED, DUPLICATE_PAYMENT_RISK review")
    void lateCaptureWithLiveLink() {
        String payId = randomId("pay_U8l");
        Campaign campaign = webhookCampaign(payId, CampaignStatus.EXECUTING);
        String plink = "plink_" + randomId("U8l");
        ActionIntent link = intent(campaign, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.AWAITING_PAYMENT, plink);

        ReconciliationResult result = capturedOriginal(payId);

        assertEquals(ReconciliationResult.Status.RECONCILED_SUCCESS, result.getStatus());
        assertNull(result.getActionIntentId(), "no intent is credited with the money");
        Campaign after = reload(campaign);
        assertEquals(CampaignStatus.RECOVERED, after.getCurrentState());
        assertEquals(RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE, after.getRecoveryAttribution());
        assertEquals(ActionIntentStatus.CANCELLED, status(link));
        ReviewQueue review = pendingReview(campaign).orElseThrow();
        assertTrue(review.getReason().startsWith("DUPLICATE_PAYMENT_RISK"), review.getReason());
        assertTrue(review.getReason().contains(plink), "review names the link to cancel at the provider");
        List<String> trail = auditTypes(campaign);
        assertTrue(trail.containsAll(List.of("INTENT_CANCELLED_ORIGINAL_PAYMENT_CAPTURED", "ORIGINAL_PAYMENT_LATE_CAPTURE", "DUPLICATE_PAYMENT_RISK")), trail.toString());
        assertTrue(mock.getRecordedRequests().isEmpty(), "no provider request of any kind: the cancel endpoint is not wired (fallback)");

        // A second capture event for the same payment (new event id; same id is rejected at ingestion) changes nothing more.
        ReconciliationResult again = reconciliation.reconcileWebhookEvent(webhook("payment.captured_" + payId + "_redelivered", "payment.captured",
                paymentPayload("payment.captured", payId, "captured", "")));
        assertEquals(ReconciliationResult.Status.SKIPPED_ALREADY_FINALIZED, again.getStatus());
        assertEquals(1, auditTypes(campaign).stream().filter("ORIGINAL_PAYMENT_LATE_CAPTURE"::equals).count());
    }

    @Test
    @DisplayName("Late capture before dispatch: READY intent CANCELLED, campaign RECOVERED, no duplicate-payment review")
    void lateCaptureBeforeDispatch() {
        String payId = randomId("pay_U8r");
        Campaign campaign = webhookCampaign(payId, CampaignStatus.ACTION_PENDING);
        ActionIntent ready = intent(campaign, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.READY, null);

        capturedOriginal(payId);

        assertEquals(ActionIntentStatus.CANCELLED, status(ready));
        assertEquals(CampaignStatus.RECOVERED, reload(campaign).getCurrentState());
        assertTrue(pendingReview(campaign).isEmpty(), "nothing was sent, so nothing can be paid twice");
        new ActionScheduler(claimService, executor, "worker-u8", 50, true, true).pollAndExecuteDueActions();
        assertEquals(0, provider.createCalls(ProviderAttemptKey.forIntent(ready.getId())), "a cancelled intent is never executed");
    }

    @Test
    @DisplayName("Late capture on a campaign before any action (ELIGIBLE): RECOVERED with late-capture attribution, never chased")
    void lateCaptureBeforeAnyAction() {
        String payId = randomId("pay_U8e");
        Campaign campaign = webhookCampaign(payId, CampaignStatus.ELIGIBLE);

        capturedOriginal(payId);

        Campaign after = reload(campaign);
        // Bug sweep behaviour change (item 2): the money is back, so the campaign is RECOVERED (was CANCELLED, an M2 deviation:
        // the state machine had no pre-action -> RECOVERED edge). The attribution keeps it out of MEND's recoveries.
        assertEquals(CampaignStatus.RECOVERED, after.getCurrentState());
        assertEquals(RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE, after.getRecoveryAttribution());
    }

    /** Bug sweep item 2 regression: every pre-action state ends RECOVERED (not CANCELLED) on a late capture of the original payment. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CampaignStatus.class, names = {"CREATED", "CLASSIFIED", "ELIGIBLE", "SCHEDULED"})
    @DisplayName("Late capture before any action, from each pre-action state: RECOVERED, ORIGINAL_PAYMENT_LATE_CAPTURE, audited")
    void lateCaptureBeforeAnyActionFromEveryPreActionState(CampaignStatus preActionState) {
        String payId = randomId("pay_BS2");
        Campaign campaign = webhookCampaign(payId, preActionState);

        capturedOriginal(payId);

        Campaign after = reload(campaign);
        assertEquals(CampaignStatus.RECOVERED, after.getCurrentState());
        assertEquals(RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE, after.getRecoveryAttribution());
        List<String> trail = auditTypes(campaign);
        assertTrue(trail.contains("ORIGINAL_PAYMENT_LATE_CAPTURE"), trail.toString());
        assertFalse(after.recoveredByMend(), "a late capture is never a MEND recovery");
    }

    @Test
    @DisplayName("Late capture while a worker is mid-call: intent CANCELLED, review names the attempt key, the worker's result is discarded")
    void lateCaptureWhileWorkerMidCall() throws Exception {
        String payId = randomId("pay_U8w");
        Campaign campaign = webhookCampaign(payId, CampaignStatus.ACTION_PENDING);
        ActionIntent ready = intent(campaign, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.READY, null);
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(ready.getId());
        provider.blockFirstCreate(key);
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<?> worker = thread.submit(() -> new ActionScheduler(claimService, executor, "worker-u8w", 50, true, true).pollAndExecuteDueActions());
            assertTrue(provider.awaitEntered(key), "worker never reached the provider");
            assertEquals(ActionIntentStatus.EXECUTING, status(ready));

            capturedOriginal(payId);

            assertEquals(ActionIntentStatus.CANCELLED, status(ready));
            provider.release(key);
            worker.get(20, TimeUnit.SECONDS);
        } finally {
            provider.release(key);
            thread.shutdownNow();
        }
        assertEquals(ActionIntentStatus.CANCELLED, status(ready), "the worker's finalize is fenced out");
        assertEquals(CampaignStatus.RECOVERED, reload(campaign).getCurrentState());
        assertEquals(1, provider.createCalls(key));
        assertTrue(auditTypes(campaign).contains("STALE_WORKER_RESULT_DISCARDED"), "the worker's link result is audited, not applied");
        ReviewQueue review = pendingReview(campaign).orElseThrow();
        assertTrue(review.getReason().startsWith("DUPLICATE_PAYMENT_RISK") && review.getReason().contains(key.value()), review.getReason());
    }

    // ---- subscription and ambiguity ----

    @Test
    @DisplayName("A captured payment on the subscription is not evidence for this invoice: no change")
    void subscriptionCaptureIsANoOp() {
        String subscriptionId = randomId("sub_U8s");
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, randomId("pay_U8s"), null, subscriptionId);
        campaign.setCurrentState(CampaignStatus.EXECUTING);
        campaign = campaigns.save(campaign);
        ActionIntent link = intent(campaign, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.AWAITING_PAYMENT, "plink_" + randomId("U8s"));

        ReconciliationResult result = reconciliation.reconcileWebhookEvent(webhook("payment.captured_" + randomId("pay_U8n"), "payment.captured",
                paymentPayload("payment.captured", randomId("pay_U8n"), "captured", ",\"subscription_id\":\"" + subscriptionId + "\"")));

        assertEquals(ReconciliationResult.Status.SKIPPED_CAMPAIGN_LEVEL, result.getStatus());
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, status(link));
        assertEquals(CampaignStatus.EXECUTING, reload(campaign).getCurrentState());
    }

    @Test
    @DisplayName("Two intents with one provider reference: nothing changes, both campaigns get a review")
    void ambiguousReferenceIsSurfaced() {
        String plink = "plink_" + randomId("U8d");
        Campaign first = webhookCampaign(randomId("pay_U8d"), CampaignStatus.EXECUTING);
        Campaign second = webhookCampaign(randomId("pay_U8d"), CampaignStatus.EXECUTING);
        ActionIntent a = intent(first, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.AWAITING_PAYMENT, plink);
        ActionIntent b = intent(second, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.AWAITING_PAYMENT, plink);

        ReconciliationResult result = reconciliation.reconcileWebhookEvent(webhook("payment_link.paid_" + plink, "payment_link.paid",
                "{\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"" + plink + "\",\"status\":\"paid\"}},"
                        + "\"payment\":{\"entity\":{\"id\":\"" + randomId("pay_U8p") + "\",\"status\":\"captured\"}}}}"));

        assertEquals(ReconciliationResult.Status.SKIPPED_CAMPAIGN_LEVEL, result.getStatus());
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, status(a));
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, status(b));
        for (Campaign campaign : List.of(first, second)) {
            assertEquals(CampaignStatus.EXECUTING, reload(campaign).getCurrentState());
            assertTrue(pendingReview(campaign).orElseThrow().getReason().startsWith("AMBIGUOUS_PROVIDER_REFERENCE"));
        }
    }

    @Test
    @DisplayName("Intent-specific capture (the recovery link was paid): campaign RECOVERED with attribution MEND")
    void intentSpecificCaptureIsAttributedToMend() {
        Campaign campaign = webhookCampaign(randomId("pay_U8m"), CampaignStatus.EXECUTING);
        String plink = "plink_" + randomId("U8m");
        ActionIntent link = intent(campaign, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.AWAITING_PAYMENT, plink);

        ReconciliationResult result = reconciliation.reconcileWebhookEvent(webhook("payment_link.paid_" + plink, "payment_link.paid",
                "{\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"" + plink + "\",\"status\":\"paid\"}},"
                        + "\"payment\":{\"entity\":{\"id\":\"" + randomId("pay_U8q") + "\",\"status\":\"captured\"}}}}"));

        assertEquals(ReconciliationResult.Status.RECONCILED_SUCCESS, result.getStatus());
        assertEquals(link.getId(), result.getActionIntentId());
        assertEquals(ActionIntentStatus.SUCCEEDED, status(link));
        assertEquals(RecoveryAttribution.MEND, reload(campaign).getRecoveryAttribution());
    }

    // ---- U8 review fixes ----

    @Test
    @DisplayName("Through event processing: a campaign-level capture (subscription) and a redelivered original failure create no campaign")
    void campaignLevelEventsAreNotClassifiedIntoNewCampaigns() {
        String subscriptionId = randomId("sub_U8x");
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, randomId("pay_U8x"), null, subscriptionId);
        campaign.setCurrentState(CampaignStatus.EXECUTING);
        campaigns.save(campaign);
        String originalPay = randomId("pay_U8y");
        webhookCampaign(originalPay, CampaignStatus.EXECUTING);
        long before = campaigns.countByMerchantId(merchantId);

        String nextCycle = randomId("pay_U8z");
        WebhookEvent captured = webhook("payment.captured_" + nextCycle, "payment.captured",
                paymentPayload("payment.captured", nextCycle, "captured", ",\"subscription_id\":\"" + subscriptionId + "\""));
        WebhookEvent failedAgain = webhook("payment.failed_" + originalPay + "_again", "payment.failed",
                paymentPayload("payment.failed", originalPay, "failed", ""));

        for (WebhookEvent event : List.of(captured, failedAgain)) {
            assertTrue(eventProcessing.processEvent(new com.mend.events.WebhookEventMessage(event.getId(), event.getExternalEventId(),
                    merchantId, event.getEventType(), Instant.now(), null)));
        }
        assertEquals(before, campaigns.countByMerchantId(merchantId), "no campaign created from a campaign-level event");
    }

    @Test
    @DisplayName("Customer pays the recovery link after the original was captured late: audited and sent to review, never swallowed")
    void linkPaidAfterLateCaptureIsSurfaced() {
        String payId = randomId("pay_U8t");
        Campaign campaign = webhookCampaign(payId, CampaignStatus.EXECUTING);
        String plink = "plink_" + randomId("U8t");
        ActionIntent link = intent(campaign, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.AWAITING_PAYMENT, plink);
        capturedOriginal(payId);
        assertEquals(ActionIntentStatus.CANCELLED, status(link));

        ReconciliationResult paid = reconciliation.reconcileWebhookEvent(webhook("payment_link.paid_" + plink, "payment_link.paid",
                "{\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"" + plink + "\",\"status\":\"paid\"}},"
                        + "\"payment\":{\"entity\":{\"id\":\"" + randomId("pay_U8u") + "\",\"status\":\"captured\"}}}}"));

        assertEquals(ReconciliationResult.Status.SKIPPED_ALREADY_FINALIZED, paid.getStatus());
        assertEquals(ActionIntentStatus.CANCELLED, status(link), "no state is rewritten");
        assertTrue(auditTypes(campaign).contains("CAPTURED_ON_TERMINAL_CAMPAIGN"), auditTypes(campaign).toString());
        assertTrue(pendingReview(campaign).isPresent(), "a human sees the possible double payment");
    }

    @Test
    @DisplayName("A duplicate captured webhook for a SUCCEEDED intent on a RECOVERED campaign stays silent (no review)")
    void duplicateSuccessOnRecoveredCampaignIsSilent() {
        Campaign campaign = webhookCampaign(randomId("pay_U8v"), CampaignStatus.EXECUTING);
        String plink = "plink_" + randomId("U8v");
        intent(campaign, ActionType.REQUEST_CUSTOMER_ACTION, ActionIntentStatus.AWAITING_PAYMENT, plink);
        String linkPay = randomId("pay_U8w");
        String body = "{\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"" + plink + "\",\"status\":\"paid\"}},"
                + "\"payment\":{\"entity\":{\"id\":\"" + linkPay + "\",\"status\":\"captured\"}}}}";
        assertEquals(ReconciliationResult.Status.RECONCILED_SUCCESS,
                reconciliation.reconcileWebhookEvent(webhook("payment_link.paid_" + plink, "payment_link.paid", body)).getStatus());

        reconciliation.reconcileWebhookEvent(webhook("payment_link.paid_" + plink + "_dup", "payment_link.paid", body));

        assertFalse(auditTypes(campaign).contains("CAPTURED_ON_TERMINAL_CAMPAIGN"));
        assertTrue(pendingReview(campaign).isEmpty());
    }
}
