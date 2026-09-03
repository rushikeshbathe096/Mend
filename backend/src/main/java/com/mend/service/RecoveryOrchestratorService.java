package com.mend.service;

import com.mend.compliance.ComplianceDecision;
import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.strategy.RecoveryDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class RecoveryOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryOrchestratorService.class);

    private final CampaignRepository campaignRepository;
    private final CampaignLifecycleService campaignLifecycleService;
    private final RecoveryStrategyService recoveryStrategyService;
    private final ComplianceService complianceService;
    private final ActionIntentService actionIntentService;
    private final ActionIntentRepository actionIntentRepository;

    public RecoveryOrchestratorService(
            CampaignRepository campaignRepository,
            @Lazy CampaignLifecycleService campaignLifecycleService,
            RecoveryStrategyService recoveryStrategyService,
            ComplianceService complianceService,
            ActionIntentService actionIntentService,
            ActionIntentRepository actionIntentRepository) {
        this.campaignRepository = campaignRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.recoveryStrategyService = recoveryStrategyService;
        this.complianceService = complianceService;
        this.actionIntentService = actionIntentService;
        this.actionIntentRepository = actionIntentRepository;
    }

    @Transactional
    public ActionIntent orchestrateRecovery(UUID merchantId, UUID campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        // Tenant Isolation Check (Step 10)
        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign " + campaignId + " does not belong to merchant " + merchantId);
        }

        // Idempotency check: If campaign is already in ACTION_PENDING state, check for existing ActionIntent
        if (campaign.getCurrentState() == CampaignStatus.ACTION_PENDING) {
            log.info("Campaign '{}' is already in ACTION_PENDING state. Checking for existing ActionIntent...", campaignId);
            List<ActionIntent> existingIntents = actionIntentRepository.findByCampaignId(campaignId);
            if (!existingIntents.isEmpty()) {
                return existingIntents.get(0);
            }
        }

        // State Machine Check: Only trigger from ELIGIBLE state (Step 2)
        if (campaign.getCurrentState() != CampaignStatus.ELIGIBLE && campaign.getCurrentState() != CampaignStatus.ACTION_PENDING) {
            log.info("Campaign '{}' is in state '{}' (expected ELIGIBLE). Skipping recovery orchestration.", campaignId, campaign.getCurrentState());
            return null;
        }

        // STEP 3: Recovery Strategy Evaluation
        log.info("Orchestrating recovery strategy evaluation for campaign '{}'", campaignId);
        RecoveryDecision recoveryDecision = recoveryStrategyService.evaluateAndPersistStrategy(merchantId, campaignId);
        if (recoveryDecision == null) {
            log.warn("Recovery strategy evaluation returned null decision for campaign '{}'", campaignId);
            return null;
        }

        // STEP 4: Compliance Safety Gate Evaluation
        log.info("Orchestrating compliance evaluation for campaign '{}'", campaignId);
        ComplianceDecision complianceDecision = complianceService.evaluateAndPersistCompliance(merchantId, campaignId);
        if (complianceDecision == null) {
            log.warn("Compliance evaluation returned null decision for campaign '{}'", campaignId);
            return null;
        }

        // STEP 5: Action Intent Creation (Only if COMPLIANCE_ALLOWED)
        if (complianceDecision.getStatus() == ComplianceStatus.COMPLIANCE_BLOCKED) {
            log.info("Compliance BLOCKED recovery action for campaign '{}': Reason={}, Message={}",
                    campaignId, complianceDecision.getReason(), complianceDecision.getDetailMessage());
            // Do NOT create ActionIntent and do NOT transition state to ACTION_PENDING
            return null;
        }

        log.info("Compliance ALLOWED recovery action for campaign '{}'. Creating ActionIntent...", campaignId);
        ActionIntent actionIntent;
        try {
            actionIntent = actionIntentService.createActionIntentFromCompliance(merchantId, campaignId);
        } catch (ComplianceBlockedException e) {
            log.info("Action intent creation blocked by compliance exception for campaign '{}': {}", campaignId, e.getMessage());
            return null;
        }

        if (actionIntent == null) {
            log.info("No ActionIntent created for campaign '{}' (possibly NO_ACTION strategy)", campaignId);
            return null;
        }

        // STEP 6: Campaign State Transition (ELIGIBLE -> ACTION_PENDING)
        if (campaign.getCurrentState() == CampaignStatus.ELIGIBLE) {
            campaignLifecycleService.transitionState(
                    merchantId,
                    campaignId,
                    CampaignStatus.ACTION_PENDING,
                    "Action intent created: " + actionIntent.getActionType(),
                    "SYSTEM",
                    null
            );
        }

        log.info("Successfully completed recovery orchestration for campaign '{}'. ActionIntent='{}', Status=ACTION_PENDING",
                campaignId, actionIntent.getId());

        return actionIntent;
    }
}
