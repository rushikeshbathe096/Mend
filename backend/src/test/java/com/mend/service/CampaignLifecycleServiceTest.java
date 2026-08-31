package com.mend.service;

import com.mend.domain.entity.AuditLog;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.exception.InvalidStateTransitionException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
@DisplayName("Campaign Lifecycle Service Integration Tests")
public class CampaignLifecycleServiceTest extends AbstractIntegrationTest {

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private AuditService auditService;

    private UUID merchantId;
    private UUID tenantBId;
    private AuthenticatedUser merchantAdminUser;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();

        Merchant merchantA = new Merchant(merchantId, "Merchant Alpha");
        Merchant merchantB = new Merchant(tenantBId, "Merchant Beta");
        merchantRepository.save(merchantA);
        merchantRepository.save(merchantB);

        UUID userId = UUID.randomUUID();
        AuthenticatedUser.MerchantMembershipInfo membership = new AuthenticatedUser.MerchantMembershipInfo(
                merchantId, "Merchant Alpha", UUID.randomUUID(), "MERCHANT_ADMIN"
        );
        merchantAdminUser = new AuthenticatedUser(
                userId,
                "admin@alpha.com",
                "Alpha Admin",
                "ACTIVE",
                Collections.singletonList(membership)
        );
    }

    // ============================================================
    // 1. IDEMPOTENT CAMPAIGN CREATION
    // ============================================================

    @Test
    @DisplayName("Should create campaign idempotently for same merchant and payment")
    void testIdempotentCampaignCreation() {
        String paymentId = "PAY-1001";

        Campaign campaign1 = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, "CUST-HASH-1", "SUB-1");
        assertNotNull(campaign1.getId());
        assertEquals(CampaignStatus.CREATED, campaign1.getCurrentState());
        assertEquals(merchantId, campaign1.getMerchantId());

        Campaign campaign2 = campaignLifecycleService.getOrCreateCampaign(merchantId, paymentId, "CUST-HASH-1", "SUB-1");
        assertEquals(campaign1.getId(), campaign2.getId(), "Subsequent call must return existing campaign instance");
    }

    // ============================================================
    // 2. AI CLASSIFICATION HANDOFF & ELIGIBILITY EVALUATION
    // ============================================================

    @Test
    @DisplayName("Should process classification result, update state to CLASSIFIED and then ELIGIBLE for recoverable failures")
    void testProcessClassificationResultFlow() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-PAY-2001", "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        ClassificationResult result = new ClassificationResult(
                UUID.randomUUID(),
                event.getId(),
                null,
                "INSUFFICIENT_FUNDS",
                new BigDecimal("0.95"),
                "SCHEDULE_RETRY",
                "Standard insufficient funds failure",
                "v1.0.0"
        );

        Campaign campaign = campaignLifecycleService.processClassificationResult(event, result);

        assertNotNull(campaign.getId());
        assertEquals(CampaignStatus.ELIGIBLE, campaign.getCurrentState());
        assertEquals("INSUFFICIENT_FUNDS", campaign.getFailureClass());
        assertEquals(new BigDecimal("0.95"), campaign.getConfidence());

        // Verify Audit Logs
        List<AuditLog> auditLogs = auditService.getCampaignAuditLogs(campaign.getId());
        assertTrue(auditLogs.size() >= 3, "Must have CREATED, CLASSIFIED, and ELIGIBLE audit logs");
        assertEquals("CAMPAIGN_STATE_TRANSITION", auditLogs.get(0).getEventType());
    }

    @Test
    @DisplayName("Should transition campaign to EXHAUSTED for UNKNOWN or low-confidence failure classifications")
    void testProcessClassificationResultUnrecoverableFlow() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-PAY-3001", "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        ClassificationResult result = new ClassificationResult(
                UUID.randomUUID(),
                event.getId(),
                null,
                "UNKNOWN",
                new BigDecimal("0.40"),
                "MANUAL_REVIEW",
                "Unrecognized error response",
                "v1.0.0"
        );

        Campaign campaign = campaignLifecycleService.processClassificationResult(event, result);

        assertNotNull(campaign.getId());
        assertEquals(CampaignStatus.EXHAUSTED, campaign.getCurrentState());
    }

    // ============================================================
    // 3. STATE TRANSITIONS & AUDIT LOGGING
    // ============================================================

    @Test
    @DisplayName("Should enforce valid state transitions and append audit logs")
    void testValidStateTransitionsWithAudit() {
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-4001", null, null);

        // Transition CREATED -> CLASSIFIED
        campaign = campaignLifecycleService.transitionState(
                merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classification completed", "SYSTEM", null);
        assertEquals(CampaignStatus.CLASSIFIED, campaign.getCurrentState());

        // Transition CLASSIFIED -> ELIGIBLE
        campaign = campaignLifecycleService.transitionState(
                merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligibility passed", "SYSTEM", null);
        assertEquals(CampaignStatus.ELIGIBLE, campaign.getCurrentState());

        // Transition ELIGIBLE -> SCHEDULED
        campaign = campaignLifecycleService.transitionState(
                merchantId, campaign.getId(), CampaignStatus.SCHEDULED, "Retry scheduled", "SYSTEM", null);
        assertEquals(CampaignStatus.SCHEDULED, campaign.getCurrentState());

        List<AuditLog> auditLogs = auditService.getCampaignAuditLogs(campaign.getId());
        assertEquals(4, auditLogs.size()); // CREATED, CLASSIFIED, ELIGIBLE, SCHEDULED
    }

    @Test
    @DisplayName("Should reject invalid state transition (RECOVERED -> EXECUTING)")
    void testInvalidStateTransitionRejection() {
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-5001", null, null);

        // CREATED -> CLASSIFIED -> ELIGIBLE -> SCHEDULED -> ACTION_PENDING -> EXECUTING -> RECOVERED
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.SCHEDULED, "Scheduled", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ACTION_PENDING, "Pending", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.EXECUTING, "Executing", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.RECOVERED, "Recovered", "SYSTEM", null);

        assertEquals(CampaignStatus.RECOVERED, campaign.getCurrentState());

        // Attempt invalid transition RECOVERED -> EXECUTING
        final UUID cId = campaign.getId();
        assertThrows(InvalidStateTransitionException.class, () ->
                campaignLifecycleService.transitionState(merchantId, cId, CampaignStatus.EXECUTING, "Illegal transition", "SYSTEM", null));
    }

    // ============================================================
    // 4. TENANT ISOLATION
    // ============================================================

    @Test
    @DisplayName("Should enforce tenant isolation and block cross-tenant campaign access")
    void testTenantIsolation() {
        Campaign campaignA = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-ALPHA", null, null);

        // Merchant A Admin accessing Merchant A campaign -> OK
        Campaign retrieved = campaignLifecycleService.getCampaign(merchantId, campaignA.getId(), merchantAdminUser);
        assertNotNull(retrieved);

        // Merchant A Admin accessing Tenant B's campaign ID with merchantId -> Blocked by lookup
        assertThrows(TenantAccessDeniedException.class, () ->
                campaignLifecycleService.getCampaign(tenantBId, campaignA.getId(), merchantAdminUser));
    }
}
