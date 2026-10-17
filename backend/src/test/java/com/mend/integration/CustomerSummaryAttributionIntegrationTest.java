package com.mend.integration;

import com.mend.controller.CustomerController;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.AnalyticsOverviewDto;
import com.mend.dto.CustomerSummaryDto;
import com.mend.service.AnalyticsQueryService;
import com.mend.service.CampaignLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cleanup finding 3: the customer summary uses the analytics attribution rule. Only MEND recoveries are
 * "recovered"; late captures of the original payment and unattributed recoveries are reported separately.
 */
@Transactional
@DisplayName("Customer summary: recovered means recovered by MEND")
class CustomerSummaryAttributionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private CustomerController customerController;
    @Autowired private AnalyticsQueryService analytics;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private CampaignRepository campaigns;
    @Autowired private MerchantRepository merchants;
    @Autowired private WebhookEventRepository webhooks;

    private UUID merchantId;
    private String customerIdHash;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        customerIdHash = "cust_hash_" + UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Customer attribution merchant"));
        // amount (paise) | state | attribution
        campaign(49900, CampaignStatus.RECOVERED, RecoveryAttribution.MEND);                          // MEND 499.00
        campaign(25000, CampaignStatus.RECOVERED, RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE); // late 250.00
        campaign(10000, CampaignStatus.RECOVERED, null);                                            // unknown 100.00
        campaign(30000, CampaignStatus.ACTION_PENDING, null);                                       // active 300.00
    }

    private void campaign(long amountMinor, CampaignStatus state, RecoveryAttribution attribution) {
        String paymentId = "pay_cs" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                "{\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"amount\":" + amountMinor
                        + ",\"currency\":\"INR\",\"status\":\"failed\"}}}}");
        event.setMerchantId(merchantId);
        webhooks.save(event);
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, null, null);
        campaign.setCustomerIdHash(customerIdHash);
        campaign.setCurrentState(state);
        campaign.setRecoveryAttribution(attribution);
        campaigns.save(campaign);
    }

    /** A campaign whose amount cannot be resolved: payload amount missing / not integral, or no webhook at all. */
    private void campaignWithUnknownAmount(String rawAmountOrNull, boolean withWebhook, CampaignStatus state,
                                           RecoveryAttribution attribution) {
        String paymentId = "pay_cu" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        if (withWebhook) {
            String amount = rawAmountOrNull == null ? "" : "\"amount\":" + rawAmountOrNull + ",";
            WebhookEvent event = new WebhookEvent(UUID.randomUUID(), paymentId, "payment.failed", "RAZORPAY",
                    "{\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\"," + amount
                            + "\"currency\":\"INR\",\"status\":\"failed\"}}}}");
            event.setMerchantId(merchantId);
            webhooks.save(event);
        }
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, null, null);
        campaign.setCustomerIdHash(customerIdHash);
        campaign.setCurrentState(state);
        campaign.setRecoveryAttribution(attribution);
        campaigns.save(campaign);
    }

    private void addUnknownAmountCampaigns() {
        campaignWithUnknownAmount(null, true, CampaignStatus.ACTION_PENDING, null);                    // amount missing
        campaignWithUnknownAmount("100.5", true, CampaignStatus.EXHAUSTED, null);                      // not integral
        campaignWithUnknownAmount(null, false, CampaignStatus.RECOVERED, RecoveryAttribution.MEND);    // no webhook
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    @DisplayName("Late capture and unknown attribution are not counted as recovered; reported separately")
    void customerSummaryCountsOnlyMendRecoveries() {
        CustomerSummaryDto summary = customerController
                .getCustomerProfile(customerIdHash, merchantId.toString(), null).getBody().getSummary();

        assertEquals(4, summary.getTotalCampaigns());
        assertEquals(1, summary.getActiveCampaigns());
        assertEquals(1, summary.getRecoveredCampaigns(), "only the MEND-attributed recovery");
        assertMoney("499.00", summary.getTotalRecoveredAmount());
        assertMoney("1149.00", summary.getTotalFailedAmount());
        assertEquals(1, summary.getRecoveredLateCaptureCount());
        assertMoney("250.00", summary.getAmountRecoveredLateCapture());
        assertEquals(1, summary.getRecoveredAttributionUnknownCount());
        assertMoney("100.00", summary.getAmountRecoveredAttributionUnknown());

        // The list endpoint builds the same summary.
        List<CustomerSummaryDto> list = customerController.getCustomers(merchantId.toString(), null).getBody();
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).getRecoveredCampaigns());
        assertEquals(1, list.get(0).getRecoveredLateCaptureCount());
        assertEquals(1, list.get(0).getRecoveredAttributionUnknownCount());
    }

    @Test
    @DisplayName("Customer summary agrees with the analytics overview for the same campaigns")
    void customerSummaryAgreesWithAnalytics() {
        CustomerSummaryDto summary = customerController
                .getCustomerProfile(customerIdHash, merchantId.toString(), null).getBody().getSummary();
        AnalyticsOverviewDto overview = analytics.getAnalyticsOverview(merchantId, null);

        assertEquals(overview.getRecoveredCampaigns(), summary.getRecoveredCampaigns());
        assertEquals(0, overview.getAmountRecovered().compareTo(summary.getTotalRecoveredAmount()));
        assertEquals(overview.getRecoveredLateCaptureCount(), summary.getRecoveredLateCaptureCount());
        assertEquals(0, overview.getAmountRecoveredLateCapture().compareTo(summary.getAmountRecoveredLateCapture()));
        assertEquals(overview.getRecoveredAttributionUnknownCount(), summary.getRecoveredAttributionUnknownCount());
        assertEquals(0, overview.getAmountRecoveredAttributionUnknown().compareTo(summary.getAmountRecoveredAttributionUnknown()));
    }

    /** Regression (cleanup follow-up finding 2): unknown amounts are counted, never defaulted, never summed. */
    @Test
    @DisplayName("A campaign with no resolvable amount is counted in amountUnknownCount and not in totalFailedAmount")
    void unknownAmountsAreCountedNotSummed() {
        addUnknownAmountCampaigns();

        CustomerSummaryDto summary = customerController
                .getCustomerProfile(customerIdHash, merchantId.toString(), null).getBody().getSummary();

        assertEquals(7, summary.getTotalCampaigns());
        assertEquals(3, summary.getAmountUnknownCount(), "missing amount, fractional amount, no webhook");
        assertMoney("1149.00", summary.getTotalFailedAmount()); // exactly the 4 known amounts
        assertEquals(2, summary.getRecoveredCampaigns(), "the amountless MEND recovery still counts as recovered");
        assertMoney("499.00", summary.getTotalRecoveredAmount()); // ...but adds no amount
    }

    @Test
    @DisplayName("With unknown amounts, the customer summary still agrees with the analytics overview")
    void unknownAmountsAgreeWithAnalytics() {
        addUnknownAmountCampaigns();

        CustomerSummaryDto summary = customerController
                .getCustomerProfile(customerIdHash, merchantId.toString(), null).getBody().getSummary();
        AnalyticsOverviewDto overview = analytics.getAnalyticsOverview(merchantId, null);

        assertEquals(overview.getTotalCampaigns(), summary.getTotalCampaigns());
        assertEquals(overview.getAmountUnknownCount(), summary.getAmountUnknownCount());
        assertEquals(0, overview.getRevenueAtRisk().compareTo(summary.getTotalFailedAmount()));
        assertEquals(overview.getRecoveredCampaigns(), summary.getRecoveredCampaigns());
        assertEquals(0, overview.getAmountRecovered().compareTo(summary.getTotalRecoveredAmount()));
        assertEquals(0, overview.getAmountRecoveredLateCapture().compareTo(summary.getAmountRecoveredLateCapture()));
        assertEquals(0, overview.getAmountRecoveredAttributionUnknown().compareTo(summary.getAmountRecoveredAttributionUnknown()));
    }
}
