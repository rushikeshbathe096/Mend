package com.mend.controller;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.AgentDecisionRecordRepository;
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
    private AgentDecisionRecordRepository agentDecisionRecordRepository;
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
                agentDecisionRecordRepository,
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
        when(agentDecisionRecordRepository.findByCampaignIdOrderByCreatedAtDesc(campaignId))
                .thenReturn(Collections.emptyList());

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
        when(campaignLifecycleService.getOrCreateCampaign(merchantId, "pay_test_456", null, null)).thenReturn(campaign);
        when(recoveryOrchestratorService.orchestrateRecovery(merchantId, campaignId)).thenReturn(intent);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(agentDecisionRecordRepository.findByCampaignIdOrderByCreatedAtDesc(campaignId)).thenReturn(Collections.emptyList());
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
                org.mockito.ArgumentMatchers.any());
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
}
