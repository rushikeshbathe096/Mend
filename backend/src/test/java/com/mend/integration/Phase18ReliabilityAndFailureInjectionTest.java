package com.mend.integration;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.InvalidCampaignStateException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.service.ActionIntentService;
import com.mend.service.DefaultEventProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class Phase18ReliabilityAndFailureInjectionTest extends AbstractIntegrationTest {

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ActionIntentService actionIntentService;

    @Autowired
    private DefaultEventProcessingService eventProcessingService;

    private UUID merchantId;
    private Merchant merchant;

    @BeforeEach
    public void setUp() {
        merchantId = UUID.randomUUID();
        merchant = new Merchant(merchantId, "Phase 18 Merchant");
        merchant.setExternalReference("ext_m_p18");
        merchant.setStatus("ACTIVE");
        merchantRepository.saveAndFlush(merchant);
    }

    @Test
    public void testIdempotentActionIntentCreationAndComplianceProtection() {
        Campaign campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        campaign.setMerchantId(merchantId);
        campaign.setPaymentId("pay_p18_01");
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaign.setAttemptCount(1);
        campaign.setStrategy(RecoveryStrategy.RETRY_IMMEDIATELY.name());
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign.setConfidence(BigDecimal.valueOf(0.95));
        campaign.setCreatedAt(Instant.now());
        campaign.setUpdatedAt(Instant.now());
        campaignRepository.saveAndFlush(campaign);

        // First creation succeeds
        ActionIntent intent1 = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        assertNotNull(intent1);
        assertEquals(merchantId, intent1.getMerchantId());

        // Idempotency Key verification
        String expectedKey = "intent:" + campaign.getId() + ":attempt_1:RETRY_PAYMENT";
        Optional<ActionIntent> foundIntent = actionIntentRepository.findByIdempotencyKey(expectedKey);
        assertTrue(foundIntent.isPresent());
        assertEquals(intent1.getId(), foundIntent.get().getId());

        // Second creation attempt for same campaign attempt is blocked by Compliance Gate (DUPLICATE_ACTION)
        assertThrows(ComplianceBlockedException.class, () -> {
            actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        }, "Compliance Gate must block duplicate action intent creation for active attempt");
    }

    @Test
    public void testTenantIsolationOnActionIntentCreation() {
        UUID otherMerchantId = UUID.randomUUID();
        Campaign campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        campaign.setMerchantId(merchantId);
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaign.setStrategy(RecoveryStrategy.RETRY_IMMEDIATELY.name());
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign.setConfidence(BigDecimal.valueOf(0.95));
        campaign.setCreatedAt(Instant.now());
        campaign.setUpdatedAt(Instant.now());
        campaignRepository.saveAndFlush(campaign);

        assertThrows(TenantAccessDeniedException.class, () -> {
            actionIntentService.createActionIntentFromCompliance(otherMerchantId, campaign.getId());
        }, "Cross-tenant ActionIntent creation must throw TenantAccessDeniedException");
    }

    @Test
    public void testInvalidCampaignStateBlocksActionIntentCreation() {
        Campaign campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        campaign.setMerchantId(merchantId);
        campaign.setCurrentState(CampaignStatus.EXHAUSTED); // Ineligible state
        campaign.setStrategy(RecoveryStrategy.RETRY_IMMEDIATELY.name());
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign.setConfidence(BigDecimal.valueOf(0.95));
        campaign.setCreatedAt(Instant.now());
        campaign.setUpdatedAt(Instant.now());
        campaignRepository.saveAndFlush(campaign);

        assertThrows(InvalidCampaignStateException.class, () -> {
            actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        });
    }

    @Test
    public void testCancelPendingIntentsForCampaign() {
        Campaign campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        campaign.setMerchantId(merchantId);
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        campaign.setStrategy(RecoveryStrategy.RETRY_IMMEDIATELY.name());
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign.setConfidence(BigDecimal.valueOf(0.95));
        campaign.setCreatedAt(Instant.now());
        campaign.setUpdatedAt(Instant.now());
        campaignRepository.saveAndFlush(campaign);

        ActionIntent intent = actionIntentService.createActionIntentFromCompliance(merchantId, campaign.getId());
        assertNotNull(intent);

        // Cancel
        actionIntentService.cancelPendingIntentsForCampaign(merchantId, campaign.getId(), "Manual Cancellation Test");

        Optional<ActionIntent> cancelledIntent = actionIntentRepository.findById(intent.getId());
        assertTrue(cancelledIntent.isPresent());
        assertEquals(ActionIntentStatus.CANCELLED, cancelledIntent.get().getStatus());
    }
}
