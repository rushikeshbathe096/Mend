package com.mend.service;

import com.mend.domain.entity.*;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.repository.*;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
@DisplayName("Phase 7.1 Recovery Orchestration Integration Tests")
public class RecoveryOrchestrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private RecoveryOrchestratorService recoveryOrchestratorService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantConfigRepository merchantConfigRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private RecoveryDecisionRepository recoveryDecisionRepository;

    @Autowired
    private ComplianceDecisionRepository complianceDecisionRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    private UUID merchantId;
    private UUID tenantBId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();

        Merchant merchantA = new Merchant(merchantId, "Alpha Merchant");
        Merchant merchantB = new Merchant(tenantBId, "Beta Merchant");
        merchantRepository.save(merchantA);
        merchantRepository.save(merchantB);
    }

    @Test
    @DisplayName("Should successfully orchestrate recovery from classification_results to ACTION_PENDING state")
    void testEndToEndRecoveryOrchestration() {
        // Step 1: Create WebhookEvent and ClassificationResult
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-ORCH-001", "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        ClassificationResult result = new ClassificationResult(
                UUID.randomUUID(),
                event.getId(),
                null,
                "INSUFFICIENT_FUNDS",
                new BigDecimal("0.95"),
                "SCHEDULE_RETRY",
                "Soft failure due to insufficient funds",
                "v1.0.0"
        );

        // Step 2: Process classification through CampaignLifecycleService (triggers orchestration)
        Campaign campaign = campaignLifecycleService.processClassificationResult(event, result);

        // Assertion 1: Campaign reaches ACTION_PENDING
        assertNotNull(campaign.getId());
        assertEquals(CampaignStatus.ACTION_PENDING, campaign.getCurrentState());

        // Assertion 2: Exactly 1 RecoveryDecision exists in DB
        List<RecoveryDecisionEntity> recoveryDecisions = recoveryDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
        assertEquals(1, recoveryDecisions.size());
        assertNotNull(recoveryDecisions.get(0).getStrategy());

        // Assertion 3 & 4: Exactly 1 ComplianceDecision exists in DB with status COMPLIANCE_ALLOWED
        List<ComplianceDecisionEntity> complianceDecisions = complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
        assertEquals(1, complianceDecisions.size());
        assertEquals(ComplianceStatus.COMPLIANCE_ALLOWED, complianceDecisions.get(0).getStatus());

        // Assertion 5 & 6: Exactly 1 ActionIntent exists referencing the ComplianceDecision
        List<ActionIntent> actionIntents = actionIntentRepository.findByCampaignId(campaign.getId());
        assertEquals(1, actionIntents.size());
        ActionIntent intent = actionIntents.get(0);
        assertEquals(complianceDecisions.get(0).getId(), intent.getComplianceDecisionId());
        assertEquals(merchantId, intent.getMerchantId());
        assertTrue(intent.getStatus() == ActionIntentStatus.SCHEDULED || intent.getStatus() == ActionIntentStatus.READY);
    }

    @Test
    @DisplayName("Should block ActionIntent creation and preserve campaign state when compliance is BLOCKED")
    void testComplianceBlockedOrchestration() {
        // Create MerchantConfig disabling RETRY_LATER actions (only CUSTOMER_OUTREACH enabled)
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setEnabledRecoveryActions("CUSTOMER_OUTREACH");
        merchantConfigRepository.save(config);

        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-ORCH-002", "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        ClassificationResult result = new ClassificationResult(
                UUID.randomUUID(),
                event.getId(),
                null,
                "INSUFFICIENT_FUNDS",
                new BigDecimal("0.95"),
                "SCHEDULE_RETRY",
                "Soft failure due to insufficient funds",
                "v1.0.0"
        );

        // Process classification
        Campaign campaign = campaignLifecycleService.processClassificationResult(event, result);

        // Assertion 1: RecoveryDecision exists
        List<RecoveryDecisionEntity> recoveryDecisions = recoveryDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
        assertEquals(1, recoveryDecisions.size());

        // Assertion 2: ComplianceDecision exists with COMPLIANCE_BLOCKED status
        List<ComplianceDecisionEntity> complianceDecisions = complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
        assertEquals(1, complianceDecisions.size());
        assertEquals(ComplianceStatus.COMPLIANCE_BLOCKED, complianceDecisions.get(0).getStatus());

        // Assertion 3: ActionIntent is absent
        List<ActionIntent> actionIntents = actionIntentRepository.findByCampaignId(campaign.getId());
        assertTrue(actionIntents.isEmpty());

        // Assertion 4: Campaign state is ELIGIBLE (NOT ACTION_PENDING)
        Campaign refreshedCampaign = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertEquals(CampaignStatus.ELIGIBLE, refreshedCampaign.getCurrentState());
    }

    @Test
    @DisplayName("Should execute orchestration idempotently when called multiple times")
    void testIdempotentOrchestration() {
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), "EVT-ORCH-003", "payment.failed");
        event.setMerchantId(merchantId);
        event = webhookEventRepository.save(event);

        ClassificationResult result = new ClassificationResult(
                UUID.randomUUID(),
                event.getId(),
                null,
                "INSUFFICIENT_FUNDS",
                new BigDecimal("0.95"),
                "SCHEDULE_RETRY",
                "Soft failure due to insufficient funds",
                "v1.0.0"
        );

        Campaign campaign = campaignLifecycleService.processClassificationResult(event, result);

        // Run orchestration second time manually
        ActionIntent secondIntent = recoveryOrchestratorService.orchestrateRecovery(merchantId, campaign.getId());

        // Assertions: Exactly 1 record of each exists in database
        assertEquals(1, recoveryDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId()).size());
        assertEquals(1, complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaign.getId()).size());
        List<ActionIntent> intents = actionIntentRepository.findByCampaignId(campaign.getId());
        assertEquals(1, intents.size());
        assertNotNull(secondIntent);
        assertEquals(intents.get(0).getId(), secondIntent.getId());
    }

    @Test
    @DisplayName("Should enforce tenant isolation and block cross-tenant orchestration attempts")
    void testTenantIsolationOrchestration() {
        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-TENANT-TEST", null, null);

        // Attempt orchestration with Tenant B ID on Tenant A campaign -> Throws TenantAccessDeniedException
        assertThrows(TenantAccessDeniedException.class, () ->
                recoveryOrchestratorService.orchestrateRecovery(tenantBId, campaign.getId()));
    }
}
