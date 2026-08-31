package com.mend.service;

import com.mend.domain.entity.*;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.repository.*;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.security.AuthenticatedUser;
import com.mend.strategy.RecoveryDecision;
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
@DisplayName("Recovery Strategy Service Integration Tests")
public class RecoveryStrategyServiceTest extends AbstractIntegrationTest {

    @Autowired
    private RecoveryStrategyService recoveryStrategyService;

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @Autowired
    private RecoveryDecisionRepository recoveryDecisionRepository;

    @Autowired
    private AuditService auditService;

    private UUID merchantId;
    private UUID tenantBId;
    private AuthenticatedUser merchantAdminUser;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();

        Merchant merchantA = new Merchant(merchantId, "Alpha Merchant");
        Merchant merchantB = new Merchant(tenantBId, "Beta Merchant");
        merchantRepository.save(merchantA);
        merchantRepository.save(merchantB);

        UUID userId = UUID.randomUUID();
        AuthenticatedUser.MerchantMembershipInfo membership = new AuthenticatedUser.MerchantMembershipInfo(
                merchantId, "Alpha Merchant", UUID.randomUUID(), "MERCHANT_ADMIN"
        );
        merchantAdminUser = new AuthenticatedUser(
                userId,
                "admin@alpha.com",
                "Alpha Admin",
                "ACTIVE",
                Collections.singletonList(membership)
        );
    }

    @Test
    @DisplayName("Should evaluate and persist strategy decision with reasoning, versioning, and audit log")
    void testEvaluateAndPersistStrategyFlow() {
        // Create Webhook Event
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-STRAT-1", "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        // Create an eligible campaign
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-STRAT-1", "CUST-1", "SUB-1");
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);

        // Save classification result
        ClassificationResult classification = new ClassificationResult(
                UUID.randomUUID(),
                event.getId(),
                campaign.getId(),
                "INSUFFICIENT_FUNDS",
                new BigDecimal("0.95"),
                "SCHEDULE_RETRY",
                "Soft failure",
                "v1.0.0"
        );
        classificationResultRepository.save(classification);

        // Evaluate Strategy
        RecoveryDecision decision = recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());

        assertNotNull(decision);
        assertEquals(RecoveryStrategy.RETRY_LATER, decision.getStrategy());
        assertEquals("v1.0", decision.getPolicyVersion());
        assertNotNull(decision.getReason());

        // Verify entity persistence in DB
        List<RecoveryDecisionEntity> persistedDecisions = recoveryDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
        assertFalse(persistedDecisions.isEmpty());
        assertEquals(RecoveryStrategy.RETRY_LATER, persistedDecisions.get(0).getStrategy());
        assertEquals("v1.0", persistedDecisions.get(0).getPolicyVersion());

        // Verify Campaign entity updated
        Campaign updatedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals("RETRY_LATER", updatedCampaign.getStrategy());

        // Verify Audit Log
        List<AuditLog> auditLogs = auditService.getCampaignAuditLogs(campaign.getId());
        assertTrue(auditLogs.stream().anyMatch(log -> "RECOVERY_STRATEGY_DETERMINED".equals(log.getEventType())));
    }

    @Test
    @DisplayName("Should maintain idempotency and avoid creating duplicate strategy decisions on repeated evaluation")
    void testIdempotency() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-STRAT-2", "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-STRAT-2", null, null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED, "Classified", "SYSTEM", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.ELIGIBLE, "Eligible", "SYSTEM", null);

        ClassificationResult classification = new ClassificationResult(
                UUID.randomUUID(), event.getId(), campaign.getId(),
                "BANK_DECLINED", new BigDecimal("0.90"), "RETRY", "Bank soft decline", "v1.0.0"
        );
        classificationResultRepository.save(classification);

        // First Evaluation
        RecoveryDecision decision1 = recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());
        int initialCount = recoveryDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId()).size();

        // Second Evaluation (Idempotent call)
        RecoveryDecision decision2 = recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaign.getId());
        int countAfterSecond = recoveryDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId()).size();

        assertEquals(decision1.getStrategy(), decision2.getStrategy());
        assertEquals(initialCount, countAfterSecond, "Repeated evaluation must not generate duplicate strategy records");
    }

    @Test
    @DisplayName("Should enforce tenant isolation and reject cross-tenant strategy access or evaluation")
    void testTenantIsolation() {
        Campaign campaignA = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-STRAT-TENANT", null, null);

        // Tenant B attempting to evaluate Merchant A campaign -> Blocked by TenantAccessDeniedException
        assertThrows(TenantAccessDeniedException.class, () ->
                recoveryStrategyService.evaluateAndPersistStrategy(tenantBId, campaignA.getId()));

        // Tenant B attempting to get strategy history for Merchant A campaign -> Blocked
        assertThrows(TenantAccessDeniedException.class, () ->
                recoveryStrategyService.getStrategyHistory(tenantBId, campaignA.getId(), merchantAdminUser));
    }
}
