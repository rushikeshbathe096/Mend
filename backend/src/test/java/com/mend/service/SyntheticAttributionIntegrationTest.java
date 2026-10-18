package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ComplianceDecisionEntity;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.*;
import com.mend.domain.repository.*;
import com.mend.dto.AnalyticsOverviewDto;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.dto.payment.PaymentExecutionStatus;
import com.mend.provider.mock.MockPaymentProvider;
import com.mend.testsupport.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Bug sweep item 5: a mock-mode SyntheticCaptured recovery is attributed SYNTHETIC and never counted as MEND's. */
class SyntheticAttributionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ActionIntentClaimService claimService;
    @Autowired private ActionExecutionService executor;
    @Autowired private AnalyticsQueryService analytics;
    @Autowired private ActionIntentRepository intents;
    @Autowired private CampaignRepository campaigns;
    @Autowired private MerchantRepository merchants;
    @Autowired private ComplianceDecisionRepository complianceDecisions;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private MockPaymentProvider mock;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        mock.reset();
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Synthetic merchant"));
    }

    @AfterEach
    void tearDown() {
        mock.reset();
    }

    /** ACTION_PENDING campaign with a ₹499.00 original failure and a READY synthetic-debit (RETRY_PAYMENT) intent. */
    private ActionIntent readyDebitIntent() {
        String paymentId = "pay_bs5" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
                merchantId, null, RecoveryStrategy.RETRY_LATER, ComplianceStatus.COMPLIANCE_ALLOWED, ComplianceReason.ALLOWED,
                // M3 behaviour change: was a v1.x version, which is now LEGACY_DECISION at dispatch (see TestCompliance)
                "Compliant action", com.mend.testsupport.TestCompliance.CURRENT_POLICY));
        ActionIntent intent = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, ActionType.RETRY_PAYMENT.name(),
                RecoveryStrategy.RETRY_LATER.name(), compliance.getId(), ActionIntentStatus.READY,
                "intent:" + campaign.getId() + ":attempt_1:" + ActionType.RETRY_PAYMENT.name(), Instant.now().minusSeconds(5));
        intent.setInvoiceKey(campaign.getInvoiceKey());
        return intents.save(intent);
    }

    @Test
    @DisplayName("SyntheticCaptured: campaign RECOVERED with attribution SYNTHETIC; analytics reports it separately, not as MEND")
    void syntheticCaptureIsAttributedSynthetic() {
        ActionIntent intent = readyDebitIntent();
        mock.setSimulatedStatus(PaymentExecutionStatus.SUCCESS);

        ActionIntent claimed = claimService.claimSpecificIntent(intent.getId(), "bs5-worker").orElseThrow();
        executor.executeActionIntent(intent.getId(), "bs5-worker", claimed.getClaimToken());

        assertEquals(ActionIntentStatus.SUCCEEDED, intents.findById(intent.getId()).orElseThrow().getStatus(), "precondition: synthetic capture");
        Campaign campaign = campaigns.findById(intent.getCampaignId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, campaign.getCurrentState());
        assertEquals(RecoveryAttribution.SYNTHETIC, campaign.getRecoveryAttribution());
        assertFalse(campaign.recoveredByMend());

        AnalyticsOverviewDto overview = analytics.getAnalyticsOverview(merchantId, TestUsers.memberOf(merchantId));
        assertEquals(0, overview.getRecoveredCampaigns(), "simulated money is never a MEND recovery");
        assertEquals(0, BigDecimal.ZERO.compareTo(overview.getAmountRecovered()));
        assertEquals(1, overview.getRecoveredSyntheticCount());
        assertEquals(0, new BigDecimal("499.00").compareTo(overview.getAmountRecoveredSynthetic()));
        assertEquals(0, overview.getRecoveredAttributionUnknownCount(), "no longer reported as attribution unknown");
    }
}
