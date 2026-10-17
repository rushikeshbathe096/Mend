package com.mend.service;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.exception.InvalidStateTransitionException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Bug sweep item 2: the lifecycle service is the only way to the pre-action → RECOVERED edges, and only for a late capture. */
class CampaignRecoverIntegrationTest extends AbstractIntegrationTest {

    @Autowired private CampaignLifecycleService lifecycle;
    @Autowired private CampaignRepository campaigns;
    @Autowired private MerchantRepository merchants;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "Recover Merchant"));
    }

    private Campaign eligibleCampaign() {
        Campaign campaign = lifecycle.getOrCreateCampaign(merchantId, "PAY-BS2-" + UUID.randomUUID(), "CUST-BS2", null);
        lifecycle.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        return lifecycle.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);
    }

    @Test
    @DisplayName("recover(ELIGIBLE, ORIGINAL_PAYMENT_LATE_CAPTURE): RECOVERED and the attribution are written together")
    void lateCaptureRecoversFromPreActionState() {
        Campaign campaign = eligibleCampaign();

        lifecycle.recover(merchantId, campaign.getId(), RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE, "late capture", "SYSTEM", null);

        Campaign after = campaigns.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.RECOVERED, after.getCurrentState());
        assertEquals(RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE, after.getRecoveryAttribution());
    }

    @Test
    @DisplayName("Other callers cannot use the edge: transitionState, recover with MEND, recover with no attribution")
    void otherCallersCannotUseTheEdge() {
        Campaign campaign = eligibleCampaign();
        UUID id = campaign.getId();

        assertThrows(InvalidStateTransitionException.class,
                () -> lifecycle.transitionState(merchantId, id, CampaignStatus.RECOVERED, "x", "SYSTEM", null));
        assertThrows(InvalidStateTransitionException.class,
                () -> lifecycle.recover(merchantId, id, RecoveryAttribution.MEND, "x", "SYSTEM", null));
        assertThrows(InvalidStateTransitionException.class,
                () -> lifecycle.recover(merchantId, id, null, "x", "SYSTEM", null));
        assertThrows(TenantAccessDeniedException.class,
                () -> lifecycle.recover(UUID.randomUUID(), id, RecoveryAttribution.ORIGINAL_PAYMENT_LATE_CAPTURE, "x", "SYSTEM", null));

        Campaign after = campaigns.findById(id).orElseThrow();
        assertEquals(CampaignStatus.ELIGIBLE, after.getCurrentState());
        assertNull(after.getRecoveryAttribution());
    }
}
