package com.mend.integration;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.ComplianceDecisionEntity;
import com.mend.domain.entity.Merchant;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.exception.ComplianceBlockedException;
import com.mend.provider.PaymentProvider;
import com.mend.scheduler.ActionScheduler;
import com.mend.service.ActionExecutionService;
import com.mend.service.ActionIntentClaimService;
import com.mend.testsupport.TestRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * M3 follow-up (ADR-003 Consequences): a READY intent created before M3 carries an AI-gated compliance decision
 * (policy v1.x). At the pre-dispatch re-check it fails closed with LEGACY_DECISION: no provider call, the intent ends
 * FAILED (audited), the campaign goes back to ELIGIBLE with a LEGACY_DECISION review, and no row is rewritten.
 */
class LegacyDecisionDispatchTest extends AbstractIntegrationTest {

    @Autowired private MerchantRepository merchants;
    @Autowired private CampaignRepository campaigns;
    @Autowired private ClassificationResultRepository classifications;
    @Autowired private ComplianceDecisionRepository complianceDecisions;
    @Autowired private ActionIntentRepository intents;
    @Autowired private ActionIntentClaimService claimService;
    @Autowired private ActionExecutionService executionService;
    @Autowired private ActionScheduler scheduler;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecoveryDecisionRepository recoveryDecisions;
    @Autowired private com.mend.service.HumanApprovalService humanApprovalService;
    @MockitoSpyBean private PaymentProvider provider;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE action_intents SET scheduled_at = scheduled_at + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Legacy decision merchant"));
    }

    @AfterEach
    void tearDown() {
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    /** A campaign as M3 found it: AI-era classification (no taxonomy version), a v1.0 ALLOWED decision, a READY intent. */
    private ActionIntent preM3Intent(String policyVersion) {
        return preM3Intent(policyVersion, false);
    }

    private ActionIntent preM3Intent(String policyVersion, boolean linkedRecoveryDecision) {
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_legacy" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign.setConfidence(new BigDecimal("0.92"));
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaign = campaigns.saveAndFlush(campaign);
        ClassificationResult legacyClass = classifications.saveAndFlush(new ClassificationResult(UUID.randomUUID(), null, campaign.getId(),
                "INSUFFICIENT_FUNDS", new BigDecimal("0.92"), "RETRY_LATER", "AI analysis", "v1.0.0-bounded-heuristic"));
        UUID recoveryDecisionId = !linkedRecoveryDecision ? null : recoveryDecisions.saveAndFlush(new com.mend.domain.entity.RecoveryDecisionEntity(
                UUID.randomUUID(), campaign.getId(), merchantId, legacyClass.getId(), RecoveryStrategy.CUSTOMER_ACTION_REQUIRED,
                "pre-M3 AI recommendation", "NORMAL", new BigDecimal("0.92"), "v1.0")).getId();
        ComplianceDecisionEntity decision = complianceDecisions.saveAndFlush(new ComplianceDecisionEntity(null, campaign.getId(),
                merchantId, recoveryDecisionId, RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, ComplianceStatus.COMPLIANCE_ALLOWED,
                ComplianceReason.ALLOWED, "pre-M3 AI-gated decision", policyVersion));
        return intents.saveAndFlush(new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, "REQUEST_CUSTOMER_ACTION",
                "CUSTOMER_ACTION_REQUIRED", decision.getId(), ActionIntentStatus.READY, "intent:" + UUID.randomUUID(),
                Instant.now().minusSeconds(60)));
    }

    @Test
    @DisplayName("A pre-M3 (v1.0) decision cannot dispatch: LEGACY_DECISION, intent FAILED, campaign ELIGIBLE + review, no rewrite")
    void legacyDecisionFailsClosedAndRoutesToReview() {
        ActionIntent intent = preM3Intent("v1.0");
        Map<String, Object> decisionBefore = jdbc.queryForMap("SELECT status, reason, policy_version, detail_message FROM compliance_decisions WHERE id = ?",
                intent.getComplianceDecisionId());

        ActionIntent claimed = claimService.claimSpecificIntent(intent.getId(), "legacy-worker").orElseThrow();
        ComplianceBlockedException blocked = assertThrows(ComplianceBlockedException.class,
                () -> executionService.executeActionIntent(claimed.getId(), "legacy-worker", claimed.getClaimToken()));

        assertTrue(blocked.getMessage().contains("LEGACY_DECISION"), blocked.getMessage());
        verify(provider, never()).createPaymentLink(any(), any());
        assertEquals(ActionIntentStatus.FAILED, intents.findById(intent.getId()).orElseThrow().getStatus());
        assertEquals(CampaignStatus.ELIGIBLE, campaigns.findById(intent.getCampaignId()).orElseThrow().getCurrentState());
        String review = jdbc.queryForObject("SELECT reason FROM review_queue WHERE campaign_id = ? AND status = 'PENDING'",
                String.class, intent.getCampaignId());
        assertTrue(review.startsWith("LEGACY_DECISION:"), review);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE campaign_id = ? AND event_type = 'ACTION_COMPLIANCE_BLOCKED' "
                + "AND reason LIKE '%LEGACY_DECISION%'", Long.class, intent.getCampaignId()));
        assertEquals(decisionBefore, jdbc.queryForMap("SELECT status, reason, policy_version, detail_message FROM compliance_decisions WHERE id = ?",
                intent.getComplianceDecisionId()), "the historical decision is not rewritten");
    }

    @Test
    @DisplayName("M4 U4.2: the LEGACY_DECISION block is logged as one DISPATCH BLOCKED row with the legacy policy version")
    void legacyDecisionBlockIsLogged() {
        ActionIntent intent = preM3Intent("v1.0");
        ActionIntent claimed = claimService.claimSpecificIntent(intent.getId(), "legacy-worker").orElseThrow();
        assertThrows(ComplianceBlockedException.class,
                () -> executionService.executeActionIntent(claimed.getId(), "legacy-worker", claimed.getClaimToken()));

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT chosen, reason_code, compliance_version, assignment_version, intent_id "
                + "FROM decision_log WHERE campaign_id = ? AND decision_point = 'DISPATCH'", intent.getCampaignId());
        assertEquals(1, rows.size());
        assertEquals("BLOCKED", rows.get(0).get("chosen"));
        assertEquals("LEGACY_DECISION", rows.get(0).get("reason_code"));
        assertEquals("v1.0", rows.get(0).get("compliance_version"));
        assertNull(rows.get(0).get("assignment_version"), "a pre-M4 campaign is UNASSIGNED");
        assertEquals(intent.getId(), rows.get(0).get("intent_id"));
    }

    @Test
    @DisplayName("The scheduler path is fenced the same way (no provider call for a pre-M3 decision)")
    void schedulerDoesNotDispatchLegacyDecision() {
        ActionIntent intent = preM3Intent("v1.0.0");
        scheduler.pollAndExecuteDueActions();
        verify(provider, never()).createPaymentLink(any(), any());
        assertEquals(ActionIntentStatus.FAILED, intents.findById(intent.getId()).orElseThrow().getStatus());
        assertEquals(CampaignStatus.ELIGIBLE, campaigns.findById(intent.getCampaignId()).orElseThrow().getCurrentState());
    }

    @Test
    @DisplayName("Approving the LEGACY_DECISION review re-evaluates under the current policy (never reuses the v1.0 decision)")
    void approvingTheReviewReEvaluatesUnderTheCurrentPolicy() {
        ActionIntent intent = preM3Intent("v1.0", true);   // linked recovery decision: the idempotency shortcut's case
        ActionIntent claimed = claimService.claimSpecificIntent(intent.getId(), "legacy-worker").orElseThrow();
        assertThrows(ComplianceBlockedException.class,
                () -> executionService.executeActionIntent(claimed.getId(), "legacy-worker", claimed.getClaimToken()));
        UUID reviewId = jdbc.queryForObject("SELECT id FROM review_queue WHERE campaign_id = ? AND status = 'PENDING'",
                UUID.class, intent.getCampaignId());

        // the classification is a pre-M3 row (no taxonomy version), so the current policy blocks it
        com.mend.exception.InvalidRequestException blocked = assertThrows(com.mend.exception.InvalidRequestException.class,
                () -> humanApprovalService.approveReview(merchantId, reviewId, "re-run", com.mend.testsupport.TestUsers.memberOf(merchantId)));
        assertTrue(blocked.getMessage().contains("UNKNOWN_CLASSIFICATION"), blocked.getMessage());
        assertEquals(1, intents.findByCampaignId(intent.getCampaignId()).size(), "no new intent on the legacy decision");
        verify(provider, never()).createPaymentLink(any(), any());
    }
}

