package com.mend.integration;

import com.mend.controller.LegacyReclassificationController;
import com.mend.domain.entity.*;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.ReviewDecisionResponse;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.service.HumanApprovalService;
import com.mend.service.LegacyReclassificationService;
import com.mend.service.RecoveryOrchestratorService;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.testsupport.TestRows;
import com.mend.testsupport.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 U4.8: (a) pre-M3 campaigns are re-classified from their stored webhook into NEW deterministic rows (old rows untouched,
 * nothing acts); (b) reply reviews can be resolved without collecting.
 */
@SpringBootTest
@ActiveProfiles("test")
class M3BacklogIntegrationTest {

    @Autowired private LegacyReclassificationService reclassification;
    @Autowired private LegacyReclassificationController reclassificationController;
    @Autowired private RecoveryOrchestratorService orchestrator;
    @Autowired private HumanApprovalService humanApprovalService;
    @Autowired private MerchantRepository merchants;
    @Autowired private CampaignRepository campaigns;
    @Autowired private ClassificationResultRepository classifications;
    @Autowired private ComplianceDecisionRepository complianceDecisions;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private ActionIntentRepository intents;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private UUID merchantId;
    private User reviewer;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M3 backlog merchant"));
        reviewer = users.save(new User(UUID.randomUUID(), "m4-backlog-" + UUID.randomUUID() + "@example.com", "hash", "Reviewer"));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')", merchantId);
        TestRows.deleteMerchant(jdbc, merchantId);
        jdbc.update("UPDATE review_queue SET assigned_user_id = NULL WHERE assigned_user_id = ?", reviewer.getId());
        users.deleteById(reviewer.getId());
    }

    private AuthenticatedUser admin() {
        return new AuthenticatedUser(reviewer.getId(), reviewer.getEmail(), "Reviewer", "ACTIVE",
                List.of(new AuthenticatedUser.MerchantMembershipInfo(merchantId, "M4", UUID.randomUUID(), "MERCHANT_ADMIN")));
    }

    /** A campaign as M3 found it: an AI-era classification row (no taxonomy version), optionally with its stored webhook. */
    private Campaign preM3Campaign(boolean withWebhook, String reason) {
        String externalId = "payment.failed_pay_legacy" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        if (withWebhook) {
            WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalId, "payment.failed", "RAZORPAY",
                    "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_x\",\"amount\":49900,\"currency\":\"INR\","
                            + "\"status\":\"failed\",\"error_reason\":\"" + reason + "\"}}}}");
            event.setMerchantId(merchantId);
            webhooks.save(event);
        }
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId(externalId);
        campaign.setFailureClass("BANK_DECLINED");
        campaign.setConfidence(new BigDecimal("0.91"));
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaign = campaigns.saveAndFlush(campaign);
        classifications.saveAndFlush(new ClassificationResult(UUID.randomUUID(), null, campaign.getId(), "BANK_DECLINED",
                new BigDecimal("0.91"), "RETRY_LATER", "AI analysis", "v1.0.0-bounded-heuristic"));
        return campaign;
    }

    @Test
    @DisplayName("(a) Reclassification writes a NEW deterministic row from the stored webhook; the legacy row is untouched; nothing acts")
    void reclassificationAddsANewRow() {
        Campaign campaign = preM3Campaign(true, "card_expired");
        Map<String, Object> legacyBefore = jdbc.queryForMap("SELECT id, failure_class, confidence, model_version, taxonomy_version "
                + "FROM classification_results WHERE campaign_id = ?", campaign.getId());

        assertEquals(LegacyReclassificationService.Result.RECLASSIFIED, reclassification.reclassify(campaign));

        assertEquals(legacyBefore, jdbc.queryForMap("SELECT id, failure_class, confidence, model_version, taxonomy_version "
                + "FROM classification_results WHERE id = ?", legacyBefore.get("id")), "legacy row unchanged");
        ClassificationResult fresh = classifications.findLatestByCampaignId(campaign.getId()).orElseThrow();
        assertNotEquals(legacyBefore.get("id"), fresh.getId());
        assertEquals("INSTRUMENT_INVALID", fresh.getFailureClass());
        assertEquals(FailureTaxonomy.TABLE_VERSION, fresh.getTaxonomyVersion());
        assertNull(fresh.getConfidence());
        assertEquals(2, classifications.findByCampaignId(campaign.getId()).size());
        assertEquals(CampaignStatus.ELIGIBLE, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState(), "no state change");
        assertTrue(intents.findByCampaignId(campaign.getId()).isEmpty(), "nothing acts");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE campaign_id = ? AND event_type = 'LEGACY_RECLASSIFIED'",
                Integer.class, campaign.getId()));

        assertEquals(LegacyReclassificationService.Result.ALREADY_DETERMINISTIC, reclassification.reclassify(campaign), "idempotent");
        assertEquals(LegacyReclassificationService.Result.NO_WEBHOOK, reclassification.reclassify(preM3Campaign(false, null)));
    }

    @Test
    @DisplayName("(a) A re-evaluation after reclassification sees the deterministic class (was: UNKNOWN_CLASSIFICATION, fail closed)")
    void reEvaluationAfterReclassification() {
        Campaign before = preM3Campaign(true, "card_expired");
        orchestrator.orchestrateRecovery(merchantId, before.getId());
        assertEquals(ComplianceReason.UNKNOWN_CLASSIFICATION,
                complianceDecisions.findFirstByCampaignIdOrderByEvaluatedAtDesc(before.getId()).orElseThrow().getReason());

        Campaign after = preM3Campaign(true, "card_expired");
        reclassification.reclassify(after);
        orchestrator.orchestrateRecovery(merchantId, after.getId());
        assertEquals(ComplianceStatus.COMPLIANCE_ALLOWED,
                complianceDecisions.findFirstByCampaignIdOrderByEvaluatedAtDesc(after.getId()).orElseThrow().getStatus());
        assertEquals(1, intents.findByCampaignId(after.getId()).size());
    }

    @Test
    @DisplayName("(a) Merchant-wide run counts each result; only SYSTEM_ADMIN may run it")
    void merchantRunAndGuard() {
        preM3Campaign(true, "insufficient_funds");
        preM3Campaign(false, null);
        assertThrows(TenantAccessDeniedException.class, () -> reclassificationController.reclassify(merchantId, TestUsers.withRole(merchantId, "MERCHANT_ADMIN")));
        Map<LegacyReclassificationService.Result, Integer> counts = reclassificationController.reclassify(merchantId, TestUsers.systemAdmin()).getBody();
        assertEquals(1, counts.get(LegacyReclassificationService.Result.RECLASSIFIED));
        assertEquals(1, counts.get(LegacyReclassificationService.Result.NO_WEBHOOK));
    }

    private Campaign eligibleCampaign() {
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_rnc" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        return campaigns.saveAndFlush(campaign);
    }

    @Test
    @DisplayName("(b) A CUSTOMER_DISPUTE review resolves without collecting: review CLOSED, campaign CANCELLED, no intent, audited")
    void resolveWithoutCollecting() {
        Campaign campaign = eligibleCampaign();
        ReviewQueue review = humanApprovalService.createReview(merchantId, campaign.getId(), "CUSTOMER_DISPUTE: the reply disputes the charge");
        assertEquals(HumanApprovalService.KIND_RESOLVE_NO_COLLECT, humanApprovalService.getReview(merchantId, review.getId(), admin()).getKind());

        ReviewDecisionResponse response = humanApprovalService.resolveWithoutCollecting(merchantId, review.getId(), "dispute upheld", admin());

        assertEquals("RESOLVE_NO_COLLECT", response.getDecision());
        assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM review_queue WHERE id = ?", String.class, review.getId()));
        assertEquals(CampaignStatus.CANCELLED, campaigns.findById(campaign.getId()).orElseThrow().getCurrentState());
        assertTrue(intents.findByCampaignId(campaign.getId()).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE campaign_id = ? AND event_type = 'REVIEW_RESOLVED_NO_COLLECT'",
                Integer.class, campaign.getId()));
    }

    @Test
    @DisplayName("(b) Other review kinds cannot be resolved that way; an in-flight link blocks it (it cannot be withdrawn)")
    void resolveRefusals() {
        Campaign manual = eligibleCampaign();
        ReviewQueue manualReview = humanApprovalService.createReview(merchantId, manual.getId(), "MANUAL_REVIEW_REQUIRED: needs a human");
        assertEquals("COLLECT", humanApprovalService.getReview(merchantId, manualReview.getId(), admin()).getKind());
        assertThrows(InvalidRequestException.class, () -> humanApprovalService.resolveWithoutCollecting(merchantId, manualReview.getId(), null, admin()));

        Campaign paid = eligibleCampaign();
        ActionIntent link = new ActionIntent(UUID.randomUUID(), merchantId, paid.getId(), 1, "REQUEST_CUSTOMER_ACTION", "CUSTOMER_ACTION_REQUIRED",
                null, ActionIntentStatus.AWAITING_PAYMENT, "intent:" + paid.getId() + ":rnc", Instant.now());
        link.setInvoiceKey(paid.getInvoiceKey());
        intents.saveAndFlush(link);
        ReviewQueue saysPaid = humanApprovalService.createReview(merchantId, paid.getId(), "CUSTOMER_SAYS_PAID: the reply says it was paid");
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> humanApprovalService.resolveWithoutCollecting(merchantId, saysPaid.getId(), null, admin()));
        assertTrue(e.getMessage().contains("in flight"));
        assertEquals(CampaignStatus.ELIGIBLE, campaigns.findById(paid.getId()).orElseThrow().getCurrentState());
    }
}
