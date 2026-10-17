package com.mend.integration;

import com.mend.testsupport.TestUsers;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.AnalyticsFailureBreakdownDto;
import com.mend.dto.AnalyticsFunnelDto;
import com.mend.dto.AnalyticsOverviewDto;
import com.mend.dto.AnalyticsRecoveryDto;
import com.mend.dto.AnalyticsStrategyPerformanceDto;
import com.mend.service.AnalyticsQueryService;
import com.mend.service.CampaignLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cleanup Group 4 regression tests (invariants 1 and 3): analytics never invents an amount, sums minor units exactly,
 * and credits MEND only for recoveries attributed to MEND.
 */
@Transactional
@DisplayName("Analytics: honest amounts and recovery attribution")
class AnalyticsAmountAndAttributionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private AnalyticsQueryService analytics;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private CampaignRepository campaigns;
    @Autowired private MerchantRepository merchants;
    @Autowired private WebhookEventRepository webhooks;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Analytics honesty merchant"));
        // amount (paise) | state | attribution | failure class | strategy
        campaign("49900", CampaignStatus.RECOVERED, RecoveryAttribution.MEND, "FUNDS", "S");                         // MEND 499.00
        campaign("25000", CampaignStatus.RECOVERED, RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE, "FUNDS", "S"); // late 250.00
        campaign("10000", CampaignStatus.RECOVERED, null, "FUNDS", "T");                                             // legacy 100.00
        campaign(null, CampaignStatus.ACTION_PENDING, null, "NO_AMOUNT", "T");                                       // amount missing
        campaign("100.5", CampaignStatus.ACTION_PENDING, null, "NO_AMOUNT", "T");                                    // not integral
        campaignWithoutWebhook(CampaignStatus.EXHAUSTED, "NO_AMOUNT");                                              // no webhook
        campaign("30000", CampaignStatus.ACTION_PENDING, null, "OPEN", "T");                                         // at risk 300.00
    }

    private void campaign(String amountOrNull, CampaignStatus state, RecoveryAttribution attribution, String failureClass, String strategy) {
        String paymentId = "pay_ah" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String amount = amountOrNull == null ? "" : "\"amount\":" + amountOrNull + ",";
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\"," + amount + "\"currency\":\"INR\",\"status\":\"failed\"}}}}");
        event.setMerchantId(merchantId);
        webhooks.save(event);
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, null, null);
        campaign.setCurrentState(state);
        campaign.setRecoveryAttribution(attribution);
        campaign.setFailureClass(failureClass);
        campaign.setStrategy(strategy);
        campaigns.save(campaign);
    }

    private void campaignWithoutWebhook(CampaignStatus state, String failureClass) {
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId,
                "pay_ah" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), null, null);
        campaign.setCurrentState(state);
        campaign.setFailureClass(failureClass);
        campaigns.save(campaign);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    @DisplayName("Missing or invalid amounts never produce a number: excluded from sums, counted as amount unknown")
    void missingAmountNeverProducesANumber() {
        // Bug sweep behaviour change (item 1): user-facing service methods now require a user (TenantGuard);
        // these calls pass a member of the merchant, as every production caller does. Assertions unchanged.
        AnalyticsOverviewDto overview = analytics.getAnalyticsOverview(merchantId, TestUsers.memberOf(merchantId));

        assertEquals(7, overview.getTotalCampaigns());
        assertEquals(3, overview.getAmountUnknownCount(), "missing amount, fractional amount, no webhook");
        // Exactly the known amounts: 499.00 + 250.00 + 100.00 + 300.00. Any default (e.g. 1000.00) would show here.
        assertMoney("1149.00", overview.getRevenueAtRisk());
        assertMoney("300.00", overview.getAmountRemainingAtRisk());

        AnalyticsFailureBreakdownDto.FailureClassMetricDto noAmount = analytics.getAnalyticsFailureBreakdown(merchantId, TestUsers.memberOf(merchantId))
                .getFailureClasses().stream().filter(f -> f.getFailureClass().equals("NO_AMOUNT")).findFirst().orElseThrow();
        assertEquals(3, noAmount.getCount());
        assertEquals(3, noAmount.getAmountUnknownCount());
        assertMoney("0.00", noAmount.getRevenueAtRisk());
        assertMoney("0.00", noAmount.getRevenueRecovered());
    }

    @Test
    @DisplayName("Late capture of the original payment and unattributed recoveries are not counted as recovered by MEND")
    void lateCaptureIsNotCountedAsMend() {
        AnalyticsOverviewDto overview = analytics.getAnalyticsOverview(merchantId, TestUsers.memberOf(merchantId));

        assertEquals(1, overview.getRecoveredCampaigns());
        assertEquals(1, overview.getSuccessfulRecoveries());
        assertMoney("499.00", overview.getAmountRecovered());
        assertEquals(14.29, overview.getRecoveryRate(), "1 of 7 campaigns recovered by MEND");
        assertEquals(1, overview.getRecoveredLateCaptureCount());
        assertMoney("250.00", overview.getAmountRecoveredLateCapture());
        assertEquals(1, overview.getRecoveredAttributionUnknownCount());
        assertMoney("100.00", overview.getAmountRecoveredAttributionUnknown());

        AnalyticsFailureBreakdownDto.FailureClassMetricDto funds = analytics.getAnalyticsFailureBreakdown(merchantId, TestUsers.memberOf(merchantId))
                .getFailureClasses().stream().filter(f -> f.getFailureClass().equals("FUNDS")).findFirst().orElseThrow();
        assertEquals(3, funds.getCount());
        assertEquals(1, funds.getRecoveredCount());
        assertMoney("849.00", funds.getRevenueAtRisk());
        assertMoney("499.00", funds.getRevenueRecovered());

        AnalyticsStrategyPerformanceDto.StrategyMetricDto s = analytics.getAnalyticsStrategyPerformance(merchantId, TestUsers.memberOf(merchantId))
                .getStrategies().stream().filter(m -> m.getStrategyName().equals("S")).findFirst().orElseThrow();
        assertEquals(2, s.getTotalCampaigns());
        assertEquals(1, s.getRecoveredCampaigns());
        assertEquals(50.0, s.getSuccessRatePercent());
        assertMoney("499.00", s.getRevenueRecovered());

        AnalyticsRecoveryDto recovery = analytics.getAnalyticsRecovery(merchantId, TestUsers.memberOf(merchantId));
        assertEquals(50.0, recovery.getRecoveryRateByStrategy().get("S"));
        assertEquals(0.0, recovery.getRecoveryRateByStrategy().get("T"), "T's only recovery has no attribution");
        assertEquals(3L, recovery.getStatusBreakdown().get("RECOVERED"), "the state breakdown still shows every RECOVERED row");

        AnalyticsFunnelDto funnel = analytics.getAnalyticsFunnel(merchantId, TestUsers.memberOf(merchantId));
        assertEquals(1, funnel.getStages().stream().filter(st -> st.getStageName().equals("RECOVERED")).findFirst().orElseThrow().getCount());
    }
}
