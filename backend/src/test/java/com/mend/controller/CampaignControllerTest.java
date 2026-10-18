package com.mend.controller;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.ComplianceDecisionRepository;
import com.mend.domain.repository.ReviewQueueRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.dto.StartRecoveryRequest;
import com.mend.dto.StartRecoveryResponseDto;
import com.mend.security.AuthenticatedUser;
import com.mend.service.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CampaignControllerTest {

    @Mock
    private CampaignQueryService campaignQueryService;
    @Mock
    private CampaignLifecycleService campaignLifecycleService;
    @Mock
    private RecoveryOrchestratorService recoveryOrchestratorService;
    @Mock
    private ActionIntentService actionIntentService;
    @Mock
    private ActionExecutionService actionExecutionService;
    @Mock
    private ComplianceDecisionRepository complianceDecisionRepository;
    @Mock
    private ReviewQueueRepository reviewQueueRepository;
    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private ActionIntentRepository actionIntentRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private com.mend.service.ActionIntentClaimService actionIntentClaimService;

    private CampaignController campaignController;

    private final UUID merchantId = UUID.randomUUID();
    private final AuthenticatedUser authenticatedUser = new AuthenticatedUser(
            UUID.randomUUID(),
            "admin@testmerchant.com",
            "Admin User",
            "ACTIVE",
            List.of(new AuthenticatedUser.MerchantMembershipInfo(merchantId, "Test Merchant", UUID.randomUUID(), "MERCHANT_ADMIN"))
    );

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        campaignController = new CampaignController(
                campaignQueryService,
                campaignLifecycleService,
                recoveryOrchestratorService,
                actionIntentService,
                actionExecutionService,
                complianceDecisionRepository,
                reviewQueueRepository,
                campaignRepository,
                actionIntentRepository,
                auditService,
                actionIntentClaimService
        );
    }

    @Test
    void startRecovery_success() {
        StartRecoveryRequest request = new StartRecoveryRequest("pay_test_123", "cust_hash_123", "sub_123");

        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign();
        campaign.setId(campaignId);
        campaign.setMerchantId(merchantId);
        campaign.setPaymentId("pay_test_123");
        campaign.setCurrentState(CampaignStatus.EXECUTING);

        UUID intentId = UUID.randomUUID();
        ActionIntent intent = new ActionIntent();
        intent.setId(intentId);
        intent.setActionType("RETRY_PAYMENT");
        intent.setStatus(ActionIntentStatus.CLAIMED);

        when(campaignLifecycleService.getOrCreateCampaign(merchantId, "pay_test_123", "cust_hash_123", "sub_123"))
                .thenReturn(campaign);
        when(recoveryOrchestratorService.orchestrateRecovery(merchantId, campaignId))
                .thenReturn(intent);
        when(campaignRepository.findById(campaignId))
                .thenReturn(Optional.of(campaign));

        var responseEntity = campaignController.startRecovery(request, merchantId.toString(), authenticatedUser);

        assertThat(responseEntity.getStatusCode().value()).isEqualTo(200);
        StartRecoveryResponseDto body = responseEntity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCampaign()).isNotNull();

        verify(campaignLifecycleService).getOrCreateCampaign(merchantId, "pay_test_123", "cust_hash_123", "sub_123");
        verify(recoveryOrchestratorService).orchestrateRecovery(merchantId, campaignId);
    }

    private ActionIntent readyIntentForOrchestration(UUID campaignId) {
        Campaign campaign = new Campaign();
        campaign.setId(campaignId);
        campaign.setMerchantId(merchantId);
        campaign.setPaymentId("pay_test_456");
        campaign.setCurrentState(CampaignStatus.ACTION_PENDING);
        ActionIntent intent = new ActionIntent();
        intent.setId(UUID.randomUUID());
        intent.setActionType("REQUEST_CUSTOMER_ACTION");
        intent.setStatus(ActionIntentStatus.READY);
        intent.setClaimToken("claim-token-manual");
        when(campaignLifecycleService.getOrCreateCampaign(merchantId, "pay_test_456", null, null)).thenReturn(campaign);
        when(recoveryOrchestratorService.orchestrateRecovery(merchantId, campaignId)).thenReturn(intent);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        return intent;
    }

    @Test
    void startRecovery_executesOnlyAfterAtomicClaim() {
        UUID campaignId = UUID.randomUUID();
        ActionIntent intent = readyIntentForOrchestration(campaignId);
        when(actionIntentClaimService.claimSpecificIntent(org.mockito.ArgumentMatchers.eq(intent.getId()), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(intent));

        campaignController.startRecovery(new StartRecoveryRequest("pay_test_456", null, null), merchantId.toString(), authenticatedUser);

        verify(actionExecutionService).executeActionIntent(org.mockito.ArgumentMatchers.eq(intent.getId()), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("claim-token-manual"));
        // M2: the manual path never claims by saving the entity.
        verify(actionIntentRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startRecovery_doesNotExecuteWhenClaimNotAcquired() {
        UUID campaignId = UUID.randomUUID();
        ActionIntent intent = readyIntentForOrchestration(campaignId);
        when(actionIntentClaimService.claimSpecificIntent(org.mockito.ArgumentMatchers.eq(intent.getId()), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        var response = campaignController.startRecovery(new StartRecoveryRequest("pay_test_456", null, null), merchantId.toString(), authenticatedUser);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(actionExecutionService, org.mockito.Mockito.never()).executeActionIntent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    // M3 U2.3 regression: the start-recovery status comes from stored evidence (review queue, compliance decision), not
    // from the deleted agent decision records
    @Test
    void startRecovery_pendingReviewAndBlockedDecisionComeFromStoredEvidence() {
        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign();
        campaign.setId(campaignId);
        campaign.setMerchantId(merchantId);
        campaign.setPaymentId("pay_test_789");
        campaign.setCurrentState(CampaignStatus.ELIGIBLE);
        when(campaignLifecycleService.getOrCreateCampaign(merchantId, "pay_test_789", null, null)).thenReturn(campaign);
        when(recoveryOrchestratorService.orchestrateRecovery(merchantId, campaignId)).thenReturn(null);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(reviewQueueRepository.findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(campaignId, com.mend.domain.enums.ReviewQueueStatus.PENDING))
                .thenReturn(Optional.of(new com.mend.domain.entity.ReviewQueue(UUID.randomUUID(), campaignId, merchantId)));
        com.mend.domain.entity.ComplianceDecisionEntity blocked = new com.mend.domain.entity.ComplianceDecisionEntity();
        blocked.setStatus(com.mend.domain.enums.ComplianceStatus.COMPLIANCE_BLOCKED);
        when(complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaignId)).thenReturn(List.of(blocked));

        StartRecoveryResponseDto body = campaignController.startRecovery(
                new StartRecoveryRequest("pay_test_789", null, null), merchantId.toString(), authenticatedUser).getBody();

        assertThat(body).isNotNull();
        assertThat(body.isRequiresHumanApproval()).isTrue();
        assertThat(body.getComplianceStatus()).isEqualTo("COMPLIANCE_BLOCKED");
        assertThat(body.getMessage()).contains("human approval");
    }

    @Test
    void startRecovery_noDecisionReportsNoComplianceStatusAndNoApproval() {
        UUID campaignId = UUID.randomUUID();
        Campaign campaign = new Campaign();
        campaign.setId(campaignId);
        campaign.setMerchantId(merchantId);
        campaign.setPaymentId("pay_test_790");
        campaign.setCurrentState(CampaignStatus.EXHAUSTED);
        when(campaignLifecycleService.getOrCreateCampaign(merchantId, "pay_test_790", null, null)).thenReturn(campaign);
        when(recoveryOrchestratorService.orchestrateRecovery(merchantId, campaignId)).thenReturn(null);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));

        StartRecoveryResponseDto body = campaignController.startRecovery(
                new StartRecoveryRequest("pay_test_790", null, null), merchantId.toString(), authenticatedUser).getBody();

        assertThat(body).isNotNull();
        assertThat(body.isRequiresHumanApproval()).isFalse();
        // was "COMPLIANCE_ALLOWED" whenever no agent record existed: an unevaluated campaign is not "allowed"
        assertThat(body.getComplianceStatus()).isNull();
    }
}
