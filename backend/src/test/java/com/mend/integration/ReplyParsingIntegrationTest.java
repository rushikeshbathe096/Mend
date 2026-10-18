package com.mend.integration;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ComplianceDecisionEntity;
import com.mend.domain.entity.Merchant;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.repository.*;
import com.mend.dto.CustomerReplyDto;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.provider.PaymentProvider;
import com.mend.reply.ReplyParseOutcome;
import com.mend.reply.ReplyParserClient;
import com.mend.reply.ReplyParsingJob;
import com.mend.reply.ReplyPolicy;
import com.mend.security.AuthenticatedUser;
import com.mend.service.ActionExecutionService;
import com.mend.service.ActionIntentClaimService;
import com.mend.service.CustomerReplyService;
import com.mend.testsupport.TestRows;
import com.mend.testsupport.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * M3 U2.4 (ADR-003 §5): asynchronous customer-reply parsing with a fake ai-service. The suggestion is validated strictly;
 * only the deterministic ReplyPolicy acts on it, never with a financial action: no reply creates, resends or finalizes an
 * intent, causes a SAFE_RETRY or a provider POST, or marks a campaign RECOVERED.
 */
class ReplyParsingIntegrationTest extends AbstractIntegrationTest {

    @Autowired private CustomerReplyService replyService;
    @Autowired private ReplyParsingJob job;
    @Autowired private CampaignRepository campaigns;
    @Autowired private MerchantRepository merchants;
    @Autowired private ActionIntentRepository intents;
    @Autowired private ComplianceDecisionRepository complianceDecisions;
    @Autowired private ActionIntentClaimService claimService;
    @Autowired private ActionExecutionService executionService;
    @Autowired private com.mend.service.ComplianceService complianceService;
    @Autowired private com.mend.service.RecoveryStrategyService strategyService;
    @Autowired private ClassificationResultRepository classifications;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.mend.service.ExecutionReconciler reconciler;
    @MockitoBean private ReplyParserClient parser;
    @MockitoSpyBean private PaymentProvider provider;
    @MockitoSpyBean private ReplyPolicy policySpy;

    private final ObjectMapper json = new ObjectMapper();
    private UUID merchantId;
    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        // park other classes' due work so this class's job runs see only its own replies and intents
        jdbc.update("UPDATE customer_replies SET next_attempt_at = next_attempt_at + interval '30 days' WHERE status = 'PENDING'");
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Reply merchant"));
        admin = TestUsers.memberOf(merchantId);
    }

    @AfterEach
    void tearDown() {
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    private Campaign campaign(CampaignStatus state) {
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_reply" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        campaign.setCurrentState(state);
        return campaigns.saveAndFlush(campaign);
    }

    private ActionIntent intent(Campaign campaign, ActionIntentStatus status, Instant reconcileAfter) {
        ActionIntent intent = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, "REQUEST_CUSTOMER_ACTION",
                "CUSTOMER_ACTION_REQUIRED", null, status, "intent:" + campaign.getId() + ":" + UUID.randomUUID(), Instant.now());
        intent.setReconcileAfter(reconcileAfter);
        return intents.saveAndFlush(intent);
    }

    private ReplyParseOutcome.Parsed parsed(String suggestionJson) {
        return new ReplyParseOutcome.Parsed(suggestionJson == null ? null : json.readTree(suggestionJson), null,
                "gemini-2.5-flash-001", "reply-parse-v1");
    }

    private CustomerReplyDto replyAndParse(Campaign campaign, ReplyParseOutcome outcome) {
        when(parser.parse(anyString())).thenReturn(outcome);
        CustomerReplyDto reply = replyService.submit(merchantId, campaign.getId(), "customer text", admin);
        job.runOnce();
        return replyService.list(merchantId, campaign.getId(), admin).stream().filter(r -> r.id().equals(reply.id())).findFirst().orElseThrow();
    }

    private long reviews(Campaign campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM review_queue WHERE campaign_id = ?", Long.class, campaign.getId());
    }

    @Test
    @DisplayName("OPT_OUT: opt-out recorded and audited; compliance blocks new intents and the executor blocks a claimed one")
    void optOutBlocksContactAtComplianceAndDispatch() {
        Campaign campaign = campaign(CampaignStatus.ACTION_PENDING);
        ComplianceDecisionEntity allowed = complianceDecisions.saveAndFlush(new ComplianceDecisionEntity(null, campaign.getId(),
                merchantId, null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED,
                ComplianceReason.ALLOWED, "allowed before the opt-out", "v2.1"));
        ActionIntent ready = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, "REQUEST_CUSTOMER_ACTION",
                "CUSTOMER_ACTION_REQUIRED", allowed.getId(), ActionIntentStatus.READY, "intent:" + UUID.randomUUID(),
                Instant.now().minusSeconds(1));
        intents.saveAndFlush(ready);

        CustomerReplyDto reply = replyAndParse(campaign, parsed("{\"intent\":\"OPT_OUT\",\"promised_date\":null,\"abstain\":false}"));

        assertEquals("PARSED", reply.status());
        assertTrue(reply.suggestion().valid());
        assertEquals("OPT_OUT", reply.suggestion().intent());
        assertEquals("gemini-2.5-flash-001", reply.suggestion().modelId(), "model id as the provider reported it");
        assertEquals(ReplyPolicy.CONTACT_OPTED_OUT, reply.suggestion().effect());
        assertNotNull(campaigns.findById(campaign.getId()).orElseThrow().getContactOptedOutAt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE campaign_id = ? AND event_type = 'CONTACT_OPTED_OUT'",
                Long.class, campaign.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE campaign_id = ? AND reason LIKE '%customer text%'",
                Long.class, campaign.getId()), "the reply text (PII) never reaches the audit trail");

        // pre-dispatch gate: the stored ALLOWED decision predates the opt-out and does not authorize contact
        ActionIntent claimed = claimService.claimSpecificIntent(ready.getId(), "reply-test-worker").orElseThrow();
        assertThrows(ComplianceBlockedException.class,
                () -> executionService.executeActionIntent(claimed.getId(), "reply-test-worker", claimed.getClaimToken()));
        assertEquals(ActionIntentStatus.FAILED, intents.findById(ready.getId()).orElseThrow().getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE campaign_id = ? AND event_type = 'ACTION_COMPLIANCE_BLOCKED' "
                + "AND reason LIKE '%CUSTOMER_OPTED_OUT%'", Long.class, campaign.getId()), "the abandoned claim is audited");
        verify(provider, never()).createPaymentLink(any(), any());

        // a new compliance evaluation is blocked as CUSTOMER_OPTED_OUT
        jdbc.update("UPDATE campaigns SET current_state = 'ELIGIBLE', failure_class = 'INSUFFICIENT_FUNDS' WHERE id = ?", campaign.getId());
        Campaign eligible = campaigns.findById(campaign.getId()).orElseThrow();
        com.mend.testsupport.TestClassifications.saveFor(classifications, eligible);
        strategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());
        var decision = complianceService.evaluateAndPersistCompliance(merchantId, campaign.getId());
        assertEquals(ComplianceReason.CUSTOMER_OPTED_OUT, decision.getReason());
    }

    @Test
    @DisplayName("Saving a stale campaign entity never clears an opt-out (column is read-only in JPA)")
    void staleCampaignSaveKeepsOptOut() {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        Campaign stale = campaigns.findById(campaign.getId()).orElseThrow();
        replyAndParse(campaign, parsed("{\"intent\":\"OPT_OUT\",\"promised_date\":null,\"abstain\":false}"));
        stale.setAttemptCount(7);
        campaigns.saveAndFlush(stale);
        assertNotNull(campaigns.findById(campaign.getId()).orElseThrow().getContactOptedOutAt());
    }

    @Test
    @DisplayName("ALREADY_PAID: the AWAITING_PAYMENT link's evidence lookup is brought forward; never RECOVERED, no POST")
    void alreadyPaidBringsForwardTheLookupOnly() {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        ActionIntent awaiting = intent(campaign, ActionIntentStatus.AWAITING_PAYMENT, Instant.now().plusSeconds(3600));

        CustomerReplyDto reply = replyAndParse(campaign, parsed("{\"intent\":\"ALREADY_PAID\",\"promised_date\":null,\"abstain\":false}"));

        assertEquals(ReplyPolicy.PAYMENT_LOOKUP_BROUGHT_FORWARD, reply.suggestion().effect());
        ActionIntent after = intents.findById(awaiting.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, after.getStatus(), "the reply decides nothing; the lookup does");
        assertTrue(after.getReconcileAfter().isBefore(Instant.now().plusSeconds(5)), "lookup brought forward");
        assertEquals(CampaignStatus.EXECUTING, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState());
        assertEquals(0, reviews(campaign));

        // a reconciler lease (reconcile_after = now + 60 s) is never undone, and a second reply right away moves nothing
        jdbc.update("UPDATE action_intents SET reconcile_after = reconcile_after + interval '60 seconds' WHERE id = ?", awaiting.getId());
        Instant leased = intents.findById(awaiting.getId()).orElseThrow().getReconcileAfter();
        replyAndParse(campaign, parsed("{\"intent\":\"ALREADY_PAID\",\"promised_date\":null,\"abstain\":false}"));
        assertEquals(leased, intents.findById(awaiting.getId()).orElseThrow().getReconcileAfter());

        // the reconciler then runs its own read-only lookup; the replies caused no POST and no recovery
        jdbc.update("UPDATE action_intents SET reconcile_after = reconcile_after - interval '60 seconds' WHERE id = ?", awaiting.getId());
        reconciler.runCycle();
        verify(provider, never()).createPaymentLink(any(), any());
        assertNotEquals(CampaignStatus.RECOVERED, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState());
    }

    @Test
    @DisplayName("ALREADY_PAID never touches NEEDS_RECONCILIATION (SAFE_RETRY stays reconciler-owned): a review instead")
    void alreadyPaidNeverTouchesNeedsReconciliation() {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        Instant later = Instant.now().plusSeconds(3600);
        ActionIntent needsReconciliation = intent(campaign, ActionIntentStatus.NEEDS_RECONCILIATION, later);
        long versionBefore = intents.findById(needsReconciliation.getId()).orElseThrow().getVersion();

        CustomerReplyDto reply = replyAndParse(campaign, parsed("{\"intent\":\"ALREADY_PAID\",\"promised_date\":null,\"abstain\":false}"));

        assertEquals(ReplyPolicy.REVIEW_CUSTOMER_SAYS_PAID, reply.suggestion().effect());
        ActionIntent untouched = intents.findById(needsReconciliation.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, untouched.getStatus());
        assertEquals(versionBefore, untouched.getVersion().longValue());
        assertTrue(untouched.getReconcileAfter().isAfter(Instant.now().plusSeconds(3000)), "schedule unchanged");
        assertEquals(1, reviews(campaign));

        // the reconciler runs: the intent is not due, so no SAFE_RETRY resend and no POST were caused by the reply
        reconciler.runCycle();
        ActionIntent afterCycle = intents.findById(needsReconciliation.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, afterCycle.getStatus());
        assertEquals(versionBefore, afterCycle.getVersion().longValue());
        verify(provider, never()).createPaymentLink(any(), any());
    }

    @Test
    @DisplayName("ALREADY_PAID without a payment link awaiting evidence: a CUSTOMER_SAYS_PAID review, nothing else")
    void alreadyPaidWithoutLinkOpensReview() {
        Campaign campaign = campaign(CampaignStatus.EXHAUSTED);

        CustomerReplyDto reply = replyAndParse(campaign, parsed("{\"intent\":\"ALREADY_PAID\",\"promised_date\":null,\"abstain\":false}"));

        assertEquals(ReplyPolicy.REVIEW_CUSTOMER_SAYS_PAID, reply.suggestion().effect());
        assertEquals(1, reviews(campaign));
        assertTrue(jdbc.queryForObject("SELECT reason FROM review_queue WHERE campaign_id = ?", String.class, campaign.getId())
                .startsWith("CUSTOMER_SAYS_PAID:"));
        assertEquals(CampaignStatus.EXHAUSTED, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState());
    }

    @Test
    @DisplayName("DISPUTE: appends to an open review instead of opening a second one")
    void disputeAppendsToOpenReview() {
        Campaign campaign = campaign(CampaignStatus.ELIGIBLE);
        jdbc.update("INSERT INTO review_queue (id, campaign_id, merchant_id, reason, status, created_at) VALUES (?, ?, ?, ?, 'PENDING', now())",
                UUID.randomUUID(), campaign.getId(), merchantId, "MANUAL_REVIEW_REQUIRED: existing");

        CustomerReplyDto reply = replyAndParse(campaign, parsed("{\"intent\":\"DISPUTE\",\"promised_date\":null,\"abstain\":false}"));

        assertEquals(ReplyPolicy.REVIEW_CUSTOMER_DISPUTE, reply.suggestion().effect());
        assertEquals(1, reviews(campaign));
        assertTrue(jdbc.queryForObject("SELECT reason FROM review_queue WHERE campaign_id = ?", String.class, campaign.getId())
                .contains("[CUSTOMER_DISPUTE]"));
    }

    @Test
    @DisplayName("Invalid or abstained suggestions are stored and shown, with no effect; output is never coerced")
    void invalidAndAbstainedHaveNoEffect() {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);

        CustomerReplyDto invented = replyAndParse(campaign, parsed("{\"intent\":\"REFUND_NOW\",\"promised_date\":null,\"abstain\":false}"));
        assertFalse(invented.suggestion().valid());
        assertNull(invented.suggestion().intent());
        assertEquals("INTENT_NOT_IN_ENUM", invented.suggestion().validationError());
        assertEquals(ReplyPolicy.NO_EFFECT, invented.suggestion().effect());

        CustomerReplyDto noJson = replyAndParse(campaign, new ReplyParseOutcome.Parsed(null, "not_json", "gemini-2.5-flash-001", "reply-parse-v1"));
        assertEquals("MODEL_OUTPUT_NOT_JSON", noJson.suggestion().validationError());

        CustomerReplyDto abstained = replyAndParse(campaign, parsed("{\"intent\":\"OPT_OUT\",\"promised_date\":null,\"abstain\":true}"));
        assertTrue(abstained.suggestion().valid());
        assertEquals(ReplyPolicy.NO_EFFECT, abstained.suggestion().effect());

        CustomerReplyDto promise = replyAndParse(campaign, parsed("{\"intent\":\"PROMISE_TO_PAY\",\"promised_date\":\"2026-11-02\",\"abstain\":false}"));
        assertEquals(ReplyPolicy.SHOWN_ONLY, promise.suggestion().effect());
        assertEquals("2026-11-02", promise.suggestion().promisedDate().toString());

        assertNull(campaigns.findById(campaign.getId()).orElseThrow().getContactOptedOutAt());
        assertEquals(0, reviews(campaign));
    }

    @Test
    @DisplayName("No model: no suggestion; retried with backoff, FAILED at the cap; a human retry resets the budget")
    void modelUnavailableRetriesThenFails() {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        when(parser.parse(anyString())).thenReturn(new ReplyParseOutcome.ModelUnavailable());
        CustomerReplyDto reply = replyService.submit(merchantId, campaign.getId(), "stop texting me", admin);

        for (int attempt = 1; attempt <= 5; attempt++) {
            job.runOnce();
            // stored timestamps are UTC wall-clock (Hibernate Instant); DB now() is in the session time zone, so make
            // the row due relative to its own created_at
            jdbc.update("UPDATE customer_replies SET next_attempt_at = created_at - interval '1 second' WHERE id = ? AND status = 'PENDING'", reply.id());
        }
        CustomerReplyDto failed = replyService.list(merchantId, campaign.getId(), admin).get(0);
        assertEquals("FAILED", failed.status());
        assertEquals(5, failed.attempts());
        assertEquals("MODEL_UNAVAILABLE", failed.lastError());
        assertNull(failed.suggestion(), "no model, no output");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reply_suggestions WHERE reply_id = ?", Long.class, reply.id()));
        assertNull(campaigns.findById(campaign.getId()).orElseThrow().getContactOptedOutAt());

        CustomerReplyDto retried = replyService.retry(merchantId, campaign.getId(), reply.id(), admin);
        assertEquals("PENDING", retried.status());
        assertEquals(0, retried.attempts());
        assertThrows(InvalidRequestException.class, () -> replyService.retry(merchantId, campaign.getId(), reply.id(), admin),
                "only a FAILED reply can be retried");
    }

    @Test
    @DisplayName("A transient failure schedules a retry with backoff (not immediately due)")
    void transientFailureBacksOff() {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        when(parser.parse(anyString())).thenReturn(new ReplyParseOutcome.Failed("AI_SERVICE_UNREACHABLE"));
        CustomerReplyDto reply = replyService.submit(merchantId, campaign.getId(), "hello", admin);
        job.runOnce();
        // both columns were written by the same scheduleRetry update (same clock), so their difference is the backoff
        Double delaySeconds = jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (next_attempt_at - updated_at)) FROM customer_replies WHERE id = ?",
                Double.class, reply.id());
        assertEquals(30.0, delaySeconds, 1.0, "base backoff 30 s");
        assertEquals("AI_SERVICE_UNREACHABLE", replyService.list(merchantId, campaign.getId(), admin).get(0).lastError());
        assertEquals(0, job.runOnce(), "not due yet");
    }

    @Test
    @DisplayName("Untrusted ids are bounded: an over-long model id is unknown (NULL); a reply that cannot be recorded is capped")
    void untrustedValuesCannotPoisonTheJob() {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        CustomerReplyDto longId = replyAndParse(campaign, new ReplyParseOutcome.Parsed(
                json.readTree("{\"intent\":\"OTHER\",\"promised_date\":null,\"abstain\":false}"), "x".repeat(500), "m".repeat(101), "p".repeat(51)));
        assertEquals("PARSED", longId.status());
        assertNull(longId.suggestion().modelId(), "never truncated into a fabricated id");
        assertNull(longId.suggestion().promptVersion());

        // recording keeps failing (here: the policy throws): attempts still count, backoff applies, FAILED at the cap
        CustomerReplyDto reply = replyService.submit(merchantId, campaign.getId(), "dispute", admin);
        when(parser.parse(anyString())).thenReturn(parsed("{\"intent\":\"DISPUTE\",\"promised_date\":null,\"abstain\":false}"));
        doThrow(new IllegalStateException("simulated")).when(policySpy).apply(any(), any());
        for (int attempt = 1; attempt <= 5; attempt++) {
            job.runOnce();
            jdbc.update("UPDATE customer_replies SET next_attempt_at = created_at - interval '1 second' WHERE id = ? AND status = 'PENDING'", reply.id());
        }
        CustomerReplyDto failed = replyService.list(merchantId, campaign.getId(), admin).stream().filter(r -> r.id().equals(reply.id())).findFirst().orElseThrow();
        assertEquals("FAILED", failed.status());
        assertEquals("RECORD_FAILED", failed.lastError());
        verify(parser, times(5 + 1)).parse(anyString());   // 1 for the first reply above, then exactly the 5 capped attempts
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @DisplayName("The reply text (PII) never reaches the application log: parse, failure and record-failure paths")
    void replyTextIsNeverLogged(org.springframework.boot.test.system.CapturedOutput output) {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        String marker = "pii-marker-" + UUID.randomUUID();
        when(parser.parse(anyString())).thenReturn(parsed("{\"intent\":\"DISPUTE\",\"promised_date\":null,\"abstain\":false}"));
        replyService.submit(merchantId, campaign.getId(), "my card is 4111 " + marker, admin);
        job.runOnce();
        when(parser.parse(anyString())).thenReturn(new ReplyParseOutcome.Failed("AI_SERVICE_UNREACHABLE"));
        replyService.submit(merchantId, campaign.getId(), "second " + marker, admin);
        job.runOnce();
        doThrow(new IllegalStateException("simulated")).when(policySpy).apply(any(), any());
        when(parser.parse(anyString())).thenReturn(parsed("{\"intent\":\"OTHER\",\"promised_date\":null,\"abstain\":false}"));
        replyService.submit(merchantId, campaign.getId(), "third " + marker, admin);
        job.runOnce();
        assertFalse(output.getAll().contains(marker), "reply text found in the log output");
    }

    @Test
    @DisplayName("A stale result (the row was re-claimed meanwhile) writes no suggestion")
    void staleResultIsDropped() {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        CustomerReplyDto reply = replyService.submit(merchantId, campaign.getId(), "stop", admin);
        when(parser.parse(anyString())).thenAnswer(inv -> {
            jdbc.update("UPDATE customer_replies SET attempts = attempts + 1 WHERE id = ?", reply.id());   // another worker's claim
            return parsed("{\"intent\":\"OPT_OUT\",\"promised_date\":null,\"abstain\":false}");
        });
        job.runOnce();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reply_suggestions WHERE reply_id = ?", Long.class, reply.id()));
        assertNull(campaigns.findById(campaign.getId()).orElseThrow().getContactOptedOutAt());
    }

    @Test
    @DisplayName("Concurrent jobs (real threads, SKIP LOCKED): every reply is parsed exactly once")
    void concurrentJobsParseEachReplyOnce() throws Exception {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        for (int i = 0; i < 6; i++) {
            replyService.submit(merchantId, campaign.getId(), "reply " + i, admin);
        }
        AtomicInteger calls = new AtomicInteger();
        when(parser.parse(anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return parsed("{\"intent\":\"OTHER\",\"promised_date\":null,\"abstain\":false}");
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int t = 0; t < 3; t++) {
            pool.submit(() -> {
                start.await();
                return job.runOnce();
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(6, calls.get());
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM reply_suggestions s JOIN customer_replies r ON r.id = s.reply_id "
                + "WHERE r.campaign_id = ?", Long.class, campaign.getId()));
    }

    @Test
    @DisplayName("Replies are tenant-scoped: another merchant can neither add nor read them; clearing an opt-out needs MERCHANT_ADMIN")
    void repliesAreTenantScoped() {
        Campaign campaign = campaign(CampaignStatus.EXECUTING);
        replyService.submit(merchantId, campaign.getId(), "mine", admin);
        UUID otherMerchant = UUID.randomUUID();
        merchants.save(new Merchant(otherMerchant, "Other merchant"));
        try {
            AuthenticatedUser other = TestUsers.memberOf(otherMerchant);
            assertThrows(TenantAccessDeniedException.class, () -> replyService.list(merchantId, campaign.getId(), other));
            assertThrows(ResourceNotFoundException.class, () -> replyService.list(otherMerchant, campaign.getId(), other),
                    "the campaign must belong to the caller's merchant");
            assertThrows(ResourceNotFoundException.class, () -> replyService.submit(otherMerchant, campaign.getId(), "x", other));
            assertThrows(TenantAccessDeniedException.class, () -> replyService.list(merchantId, campaign.getId(), null));

            assertThrows(InvalidRequestException.class, () -> replyService.submit(merchantId, campaign.getId(), "a".repeat(2001), admin));
            assertThrows(InvalidRequestException.class, () -> replyService.submit(merchantId, campaign.getId(), "   ", admin));

            jdbc.update("UPDATE campaigns SET contact_opted_out_at = now() WHERE id = ?", campaign.getId());
            assertThrows(TenantAccessDeniedException.class,
                    () -> replyService.clearOptOut(merchantId, campaign.getId(), TestUsers.withRole(merchantId, "VIEWER")));
            assertTrue(replyService.clearOptOut(merchantId, campaign.getId(), admin));
            assertNull(campaigns.findById(campaign.getId()).orElseThrow().getContactOptedOutAt());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE campaign_id = ? AND event_type = 'CONTACT_OPT_OUT_CLEARED'",
                    Long.class, campaign.getId()));
        } finally {
            TestRows.deleteMerchant(jdbc, otherMerchant);
        }
    }

    @Test
    @DisplayName("The payment path never calls the reply parser")
    void paymentPathNeverCallsTheParser() {
        // the parser is reachable only from ReplyParsingJob (checked by type: no payment-path service depends on it)
        for (Class<?> type : List.of(com.mend.service.DefaultEventProcessingService.class, com.mend.service.ClassificationService.class,
                com.mend.service.RecoveryOrchestratorService.class, ActionExecutionService.class, com.mend.service.ExecutionReconciler.class,
                com.mend.compliance.ComplianceEngine.class, com.mend.service.ComplianceService.class,
                com.mend.service.HumanApprovalService.class, com.mend.scheduler.ActionScheduler.class,
                com.mend.controller.CampaignController.class, com.mend.service.ActionIntentService.class,
                com.mend.service.RecoveryStrategyService.class, com.mend.service.CampaignLifecycleService.class)) {
            for (var constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    assertFalse(parameter.getPackageName().equals("com.mend.reply"), type.getSimpleName() + " takes " + parameter.getName());
                }
            }
        }
        // and behaviourally: a full recovery execution (claim, dispatch, mock provider) never calls the parser
        Campaign campaign = campaign(CampaignStatus.ACTION_PENDING);
        ComplianceDecisionEntity allowed = complianceDecisions.saveAndFlush(new ComplianceDecisionEntity(null, campaign.getId(),
                merchantId, null, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED,
                ComplianceReason.ALLOWED, "allowed", "v2.1"));
        ActionIntent ready = intents.saveAndFlush(new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1,
                "REQUEST_CUSTOMER_ACTION", "CUSTOMER_ACTION_REQUIRED", allowed.getId(), ActionIntentStatus.READY,
                "intent:" + UUID.randomUUID(), Instant.now().minusSeconds(1)));
        ActionIntent claimed = claimService.claimSpecificIntent(ready.getId(), "reply-path-worker").orElseThrow();
        executionService.executeActionIntent(claimed.getId(), "reply-path-worker", claimed.getClaimToken());
        reconciler.runCycle();
        // (without a webhook amount the executor defers the intent back to READY: M2 deferral; the dispatch still ran)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE campaign_id = ? AND event_type = 'ACTION_EXECUTING'",
                Long.class, campaign.getId()), "the execution ran");
        verifyNoInteractions(parser);
    }
}
