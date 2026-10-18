package com.mend.integration;

import com.mend.compliance.ComplianceEngine;
import com.mend.domain.entity.*;
import com.mend.domain.entity.DecisionLogEntry.Point;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.experiment.Arm;
import com.mend.experiment.HoldoutAssigner;
import com.mend.provider.PaymentProvider;
import com.mend.scheduler.ActionScheduler;
import com.mend.service.*;
import com.mend.strategy.RecoveryStrategyEngine;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.ProviderFailure;
import com.mend.testsupport.TestRows;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M4 U4.2 (ADR-004 §4, AGENTS.md invariant 21): every path writes exactly one decision-log row per decision point, in the
 * deciding transaction, and the rows are immutable. Real PostgreSQL, the real scheduler and executor, mock provider.
 */
@SpringBootTest
@ActiveProfiles("test")
class DecisionLogIntegrationTest {

    @Autowired private ClassificationService classificationService;
    @Autowired private RecoveryOrchestratorService orchestrator;
    @Autowired private ActionScheduler actionScheduler;
    @Autowired private MerchantRepository merchants;
    @Autowired private MerchantConfigRepository merchantConfigs;
    @Autowired private CampaignRepository campaigns;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private ActionIntentRepository intents;
    @Autowired private DecisionLogRepository decisionLog;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ActionIntentClaimService claims;
    @Autowired private ActionExecutionService executor;
    @Autowired private HumanApprovalService humanApprovalService;
    @Autowired private UserRepository users;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @MockitoSpyBean private PaymentProvider provider;
    @MockitoSpyBean private DecisionLogService decisionLogService;

    private static final byte[] SALT = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            SALT[i] = (byte) (17 * i + 3);
        }
    }

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M4 decision-log merchant"));
        jdbc.update("INSERT INTO merchant_experiment_salts (merchant_id, purpose, salt_version, salt) VALUES (?, 'ASSIGNMENT', 1, ?)",
                merchantId, SALT);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')", merchantId);
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    private void config(int holdoutBp, String enabledActions) {
        MerchantConfig config = merchantConfigs.findByMerchantId(merchantId).orElseGet(() -> new MerchantConfig(UUID.randomUUID(), merchantId));
        config.setHoldoutRateBp(holdoutBp);
        config.setEnabledRecoveryActions(enabledActions);
        merchantConfigs.save(config);
    }

    private static String paymentIdIn(Arm arm, int rateBp) {
        for (int i = 0; i < 10_000; i++) {
            String payId = "pay_D" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
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

    private List<DecisionLogEntry> rows(Campaign campaign, Point point) {
        return decisionLog.findByCampaignIdAndDecisionPoint(campaign.getId(), point);
    }

    private ActionIntent onlyIntent(Campaign campaign) {
        List<ActionIntent> list = intents.findByCampaignId(campaign.getId());
        assertEquals(1, list.size());
        return list.get(0);
    }

    private void makeDue(ActionIntent intent) {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() - interval '1 minute', status = 'READY' WHERE id = ?", intent.getId());
    }

    @Test
    @DisplayName("TREATMENT link path: one ASSIGNMENT, one STRATEGY, one COMPLIANCE, one DISPATCH row with alternatives, propensity and versions")
    void treatmentPathWritesOneRowPerPoint() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");
        makeDue(onlyIntent(campaign));
        actionScheduler.pollAndExecuteDueActions();

        DecisionLogEntry assignment = single(rows(campaign, Point.ASSIGNMENT));
        assertEquals("TREATMENT", assignment.getChosen());
        assertEquals(List.of("TREATMENT"), assignment.getAlternatives());
        assertEquals(10_000, assignment.getPropensityBp());
        assertEquals(HoldoutAssigner.HASH_VERSION, assignment.getAssignmentVersion());
        assertEquals("HASH", assignment.getReasonCode());
        // U4.2 review: every row carries the versions in force
        assertEquals(FailureTaxonomy.TABLE_VERSION, assignment.getTaxonomyVersion());
        assertEquals(RecoveryStrategyEngine.POLICY_VERSION, assignment.getStrategyVersion());
        assertEquals(ComplianceEngine.POLICY_VERSION, assignment.getComplianceVersion());

        DecisionLogEntry strategy = single(rows(campaign, Point.STRATEGY));
        assertEquals("CUSTOMER_ACTION_REQUIRED", strategy.getChosen());
        assertEquals(List.of("CUSTOMER_ACTION_REQUIRED", "MANUAL_REVIEW", "NO_ACTION", "RETRY_IMMEDIATELY", "RETRY_LATER"), strategy.getAlternatives());
        assertEquals(10_000, strategy.getPropensityBp());
        assertEquals(FailureTaxonomy.TABLE_VERSION, strategy.getTaxonomyVersion());
        assertEquals(RecoveryStrategyEngine.POLICY_VERSION, strategy.getStrategyVersion());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM recovery_decisions WHERE id = ?", Integer.class, strategy.getSourceId()));

        DecisionLogEntry compliance = single(rows(campaign, Point.COMPLIANCE));
        assertEquals("ALLOWED", compliance.getChosen());
        assertEquals("ALLOWED", compliance.getReasonCode());
        assertEquals(List.of("ALLOWED", "BLOCKED"), compliance.getAlternatives());
        assertEquals(ComplianceEngine.POLICY_VERSION, compliance.getComplianceVersion());
        assertEquals(RecoveryStrategyEngine.POLICY_VERSION, compliance.getStrategyVersion());
        assertEquals(FailureTaxonomy.TABLE_VERSION, compliance.getTaxonomyVersion());

        DecisionLogEntry dispatch = single(rows(campaign, Point.DISPATCH));
        assertEquals("DISPATCHED", dispatch.getChosen());
        assertEquals(onlyIntent(campaign).getId(), dispatch.getIntentId());
        assertEquals(ComplianceEngine.POLICY_VERSION, dispatch.getComplianceVersion());
        assertEquals(FailureTaxonomy.TABLE_VERSION, dispatch.getTaxonomyVersion());
        assertEquals(RecoveryStrategyEngine.POLICY_VERSION, dispatch.getStrategyVersion());
        assertEquals(HoldoutAssigner.HASH_VERSION, dispatch.getAssignmentVersion());
        assertEquals(HoldoutAssigner.HASH_VERSION, strategy.getAssignmentVersion());
        assertEquals(ComplianceEngine.POLICY_VERSION, strategy.getComplianceVersion());
        assertEquals(HoldoutAssigner.HASH_VERSION, compliance.getAssignmentVersion());
        assertEquals(4, decisionLog.findByCampaignIdOrderByDecidedAtAscIdAsc(campaign.getId()).size());
    }

    @Test
    @DisplayName("CONTROL: exactly one ASSIGNMENT row (propensity = the rate, both arms as alternatives) and nothing else")
    void controlWritesOnlyTheAssignment() {
        config(2000, null);
        Campaign campaign = failPayment(paymentIdIn(Arm.CONTROL, 2000), "card_expired");
        actionScheduler.pollAndExecuteDueActions();

        DecisionLogEntry assignment = single(rows(campaign, Point.ASSIGNMENT));
        assertEquals("CONTROL", assignment.getChosen());
        assertEquals(List.of("CONTROL", "TREATMENT"), assignment.getAlternatives());
        assertEquals(2000, assignment.getPropensityBp());
        assertEquals(1, decisionLog.findByCampaignIdOrderByDecidedAtAscIdAsc(campaign.getId()).size());
    }

    @Test
    @DisplayName("TREATMENT at rate 0.2: propensity 8 000 bp")
    void treatmentPropensityAtNonZeroRate() {
        config(2000, null);
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 2000), "card_expired");
        assertEquals(8000, single(rows(campaign, Point.ASSIGNMENT)).getPropensityBp());
    }

    @Test
    @DisplayName("Compliance block (MANUAL_REVIEW from merchant config): STRATEGY with the merchant's alternatives, COMPLIANCE BLOCKED with its reason, no DISPATCH")
    void complianceBlockedPath() {
        config(0, "RETRY_LATER");
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");

        DecisionLogEntry strategy = single(rows(campaign, Point.STRATEGY));
        assertEquals("MANUAL_REVIEW", strategy.getChosen());
        assertEquals(List.of("MANUAL_REVIEW", "NO_ACTION", "RETRY_LATER"), strategy.getAlternatives());
        DecisionLogEntry compliance = single(rows(campaign, Point.COMPLIANCE));
        assertEquals("BLOCKED", compliance.getChosen());
        assertEquals("STRATEGY_NOT_SUPPORTED", compliance.getReasonCode());
        assertTrue(rows(campaign, Point.DISPATCH).isEmpty());
    }

    @Test
    @DisplayName("Opt-out found at the pre-dispatch re-check: one DISPATCH BLOCKED (CUSTOMER_OPTED_OUT), no DISPATCHED, no provider call")
    void blockedAtDispatch() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");
        ActionIntent intent = onlyIntent(campaign);
        jdbc.update("UPDATE campaigns SET contact_opted_out_at = now() WHERE id = ?", campaign.getId());
        makeDue(intent);
        actionScheduler.pollAndExecuteDueActions();

        DecisionLogEntry dispatch = single(rows(campaign, Point.DISPATCH));
        assertEquals("BLOCKED", dispatch.getChosen());
        assertEquals("CUSTOMER_OPTED_OUT", dispatch.getReasonCode());
        assertEquals(intent.getId(), dispatch.getIntentId());
        verify(provider, never()).createPaymentLink(any(), any());
    }

    @Test
    @DisplayName("Expired between claim and dispatch: one DISPATCH EXPIRED row")
    void expiredAtDispatch() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");
        ActionIntent intent = onlyIntent(campaign);
        makeDue(intent);
        ActionIntent claimed = claims.claimSpecificIntent(intent.getId(), "m4-expiry-worker").orElseThrow();
        // the claim query never hands out an expired intent, so expiry lands after the claim (time simulated in SQL)
        jdbc.update("UPDATE action_intents SET expires_at = now() - interval '1 minute' WHERE id = ?", intent.getId());
        executor.executeActionIntent(intent.getId(), "m4-expiry-worker", claimed.getClaimToken());

        DecisionLogEntry dispatch = single(rows(campaign, Point.DISPATCH));
        assertEquals("EXPIRED", dispatch.getChosen());
        assertEquals("INTENT_EXPIRED", dispatch.getReasonCode());
    }

    @Test
    @DisplayName("Idempotent re-orchestration writes no second STRATEGY or COMPLIANCE row")
    void idempotentReevaluationWritesNothing() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");
        orchestrator.orchestrateRecovery(merchantId, campaign.getId());
        orchestrator.orchestrateRecovery(merchantId, campaign.getId());
        assertEquals(1, rows(campaign, Point.STRATEGY).size());
        assertEquals(1, rows(campaign, Point.COMPLIANCE).size());
        assertEquals(1, rows(campaign, Point.ASSIGNMENT).size());
    }

    @Test
    @DisplayName("A failed log insert at the fence rolls the dispatch back: the intent is not EXECUTING and the provider is never called")
    void failedLogInsertRollsBackTheDispatch() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");
        ActionIntent intent = onlyIntent(campaign);
        // stubbing calls the spied bean through its MANDATORY transactional proxy, so it runs inside a transaction
        transactionTemplate.executeWithoutResult(tx -> doThrow(new IllegalStateException("decision log unavailable")).when(decisionLogService)
                .dispatch(any(), any(), eq(intent.getId()), eq(DecisionLogService.DISPATCHED), any(), any()));
        makeDue(intent);
        try {
            actionScheduler.pollAndExecuteDueActions();
        } catch (RuntimeException ignored) {
            // the scheduler may surface the failure; what matters is the state below
        }

        ActionIntent after = intents.findById(intent.getId()).orElseThrow();
        assertEquals(ActionIntentStatus.CLAIMED, after.getStatus(), "the fence rolled back: still CLAIMED, released later by its lease");
        assertNull(after.getDispatchedAt());
        assertTrue(rows(campaign, Point.DISPATCH).isEmpty());
        verify(provider, never()).createPaymentLink(any(), any());
    }

    @Test
    @DisplayName("Sweeper expiry of an unclaimed intent: one DISPATCH EXPIRED row (INTENT_EXPIRED_UNCLAIMED)")
    void sweeperExpiryIsLogged() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");
        ActionIntent intent = onlyIntent(campaign);
        jdbc.update("UPDATE action_intents SET expires_at = now() - interval '1 minute' WHERE id = ?", intent.getId());
        claims.processExpiredIntents();

        DecisionLogEntry dispatch = single(rows(campaign, Point.DISPATCH));
        assertEquals("EXPIRED", dispatch.getChosen());
        assertEquals("INTENT_EXPIRED_UNCLAIMED", dispatch.getReasonCode());
    }

    @Test
    @DisplayName("OD-1 approval with the override strategy: a second COMPLIANCE row with its own source and no policy strategy version")
    void approvalOverrideWritesItsOwnComplianceRow() {
        // insufficient_funds -> RETRY_LATER, which this merchant has not enabled -> MANUAL_REVIEW; the override (a link) is enabled
        config(0, "CUSTOMER_ACTION_REQUIRED");
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "insufficient_funds");
        UUID reviewId = jdbc.queryForObject("SELECT id FROM review_queue WHERE campaign_id = ? AND status = 'PENDING'", UUID.class, campaign.getId());
        User reviewer = users.save(new User(UUID.randomUUID(), "m4-reviewer-" + UUID.randomUUID() + "@example.com", "hash", "Reviewer"));
        humanApprovalService.approveReview(merchantId, reviewId, "ok", new com.mend.security.AuthenticatedUser(reviewer.getId(),
                reviewer.getEmail(), "Reviewer", "ACTIVE",
                List.of(new com.mend.security.AuthenticatedUser.MerchantMembershipInfo(merchantId, "M4", UUID.randomUUID(), "MERCHANT_ADMIN"))));
        users.deleteById(reviewer.getId());

        List<DecisionLogEntry> compliance = rows(campaign, Point.COMPLIANCE);
        assertEquals(2, compliance.size());
        assertNotEquals(compliance.get(0).getSourceId(), compliance.get(1).getSourceId());
        DecisionLogEntry override = compliance.stream().filter(r -> r.getStrategyVersion() == null).findFirst().orElseThrow();
        assertEquals("ALLOWED", override.getChosen());
        assertEquals(1, compliance.stream().filter(r -> RecoveryStrategyEngine.POLICY_VERSION.equals(r.getStrategyVersion())).count());
    }

    @Test
    @DisplayName("A failed STRATEGY log insert rolls the whole decision back: no recovery decision, no intent, no campaign")
    void failedStrategyLogRollsBackTheDecision() {
        transactionTemplate.executeWithoutResult(tx -> doThrow(new IllegalStateException("decision log unavailable"))
                .when(decisionLogService).strategy(any(), any(), any(), any()));
        String payId = paymentIdIn(Arm.TREATMENT, 0);
        assertThrows(RuntimeException.class, () -> failPayment(payId, "card_expired"));
        assertTrue(campaigns.findByMerchantIdAndPaymentId(merchantId, "payment.failed_" + payId).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recovery_decisions WHERE merchant_id = ?", Integer.class, merchantId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM decision_log WHERE merchant_id = ?", Integer.class, merchantId));
    }

    @Test
    @DisplayName("Rows are immutable: UPDATE and DELETE are rejected; the statement-level (table-wide) guard is installed")
    void immutable() {
        Campaign campaign = failPayment(paymentIdIn(Arm.TREATMENT, 0), "card_expired");
        for (String sql : List.of(
                "UPDATE decision_log SET propensity_bp = 1 WHERE campaign_id = '" + campaign.getId() + "'",
                "DELETE FROM decision_log WHERE campaign_id = '" + campaign.getId() + "'")) {
            DataAccessException e = assertThrows(DataAccessException.class, () -> jdbc.execute(sql), sql);
            assertTrue(e.getMostSpecificCause().getMessage().contains("APPEND_ONLY"), e.getMostSpecificCause().getMessage());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid "
                + "WHERE t.tgrelid = 'decision_log'::regclass AND (t.tgtype & 32) <> 0 AND (t.tgtype & 2) <> 0 "
                + "AND p.proname = 'mend_reject_modification'", Integer.class));
        assertEquals(3, decisionLog.findByCampaignIdOrderByDecidedAtAscIdAsc(campaign.getId()).size());
    }

    @Test
    @DisplayName("The database refuses a row whose chosen action is not among its alternatives")
    void chosenMustBeAnAlternative() {
        DataAccessException e = assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO decision_log (id, merchant_id, campaign_id, decision_point, chosen, alternatives, propensity_bp, decided_at) "
                        + "VALUES (?, ?, ?, 'STRATEGY', 'RETRY_LATER', '[\"NO_ACTION\"]'::jsonb, 10000, now())",
                UUID.randomUUID(), merchantId, UUID.randomUUID()));
        assertTrue(e.getMostSpecificCause().getMessage().contains("ck_decision_log_alternatives"));
    }

    private static DecisionLogEntry single(List<DecisionLogEntry> rows) {
        assertEquals(1, rows.size(), "exactly one row per decision point: " + rows.stream().map(DecisionLogEntry::getChosen).toList());
        return rows.get(0);
    }
}
