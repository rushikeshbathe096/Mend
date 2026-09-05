package com.mend.service;

import com.mend.agent.AgentContext;
import com.mend.agent.AgentDecision;
import com.mend.agent.AgentDecisionEngine;
import com.mend.compliance.ComplianceDecision;
import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.AgentDecisionRecord;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.AgentDecisionRecordRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.strategy.RecoveryDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ClassificationResultRepository classificationResultRepository;
    private final MerchantConfigRepository merchantConfigRepository;
    private final AgentDecisionRecordRepository agentDecisionRecordRepository;
    private final AgentDecisionEngine agentDecisionEngine;
    private final HumanApprovalService humanApprovalService;
    private final AuditService auditService;

    public RecoveryOrchestratorService(
            CampaignRepository campaignRepository,
            @Lazy CampaignLifecycleService campaignLifecycleService,
            RecoveryStrategyService recoveryStrategyService,
            ComplianceService complianceService,
            ActionIntentService actionIntentService,
            ActionIntentRepository actionIntentRepository,
            ClassificationResultRepository classificationResultRepository,
            MerchantConfigRepository merchantConfigRepository,
            AgentDecisionRecordRepository agentDecisionRecordRepository,
            AgentDecisionEngine agentDecisionEngine,
            HumanApprovalService humanApprovalService,
            AuditService auditService) {
        this.campaignRepository = campaignRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.recoveryStrategyService = recoveryStrategyService;
        this.complianceService = complianceService;
        this.actionIntentService = actionIntentService;
        this.actionIntentRepository = actionIntentRepository;
        this.classificationResultRepository = classificationResultRepository;
        this.merchantConfigRepository = merchantConfigRepository;
        this.agentDecisionRecordRepository = agentDecisionRecordRepository;
        this.agentDecisionEngine = agentDecisionEngine;
        this.humanApprovalService = humanApprovalService;
        this.auditService = auditService;
    }

    @Transactional
    public ActionIntent orchestrateRecovery(UUID merchantId, UUID campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        // Tenant Isolation Check
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

        // State Machine Check: Only trigger from ELIGIBLE state
        if (campaign.getCurrentState() != CampaignStatus.ELIGIBLE && campaign.getCurrentState() != CampaignStatus.ACTION_PENDING) {
            log.info("Campaign '{}' is in state '{}' (expected ELIGIBLE). Skipping recovery orchestration.", campaignId, campaign.getCurrentState());
            return null;
        }

        // STEP 1: Build AgentContext
        ClassificationResult classification = classificationResultRepository.findLatestByCampaignId(campaignId).orElse(null);
        MerchantConfig config = merchantConfigRepository.findByMerchantId(merchantId).orElse(null);
        AgentContext agentContext = AgentContext.build(
                campaign,
                classification,
                config,
                List.of(),
                List.of(),
                "COMPLIANCE_ALLOWED",
                List.of("RETRY_PAYMENT", "REQUEST_CUSTOMER_ACTION", "PAUSE_SUBSCRIPTION", "OFFER_DISCOUNT")
        );

        // STEP 2: Execute Agent Decision Engine
        AgentDecision decision = agentDecisionEngine.decide(agentContext);

        // Persist Agent Decision Record for Memory & Auditability
        AgentDecisionRecord record = new AgentDecisionRecord(
                decision.decisionId(),
                campaignId,
                merchantId,
                campaign.getPaymentId(),
                decision.decision(),
                decision.selectedAction(),
                decision.confidence(),
                decision.reasoning(),
                decision.evidence() != null ? String.join(",", decision.evidence()) : "",
                decision.nextStep(),
                decision.stopReason(),
                decision.modelVersion(),
                decision.requiresHumanApproval(),
                decision.complianceStatus(),
                "EVALUATED"
        );
        agentDecisionRecordRepository.save(record);

        auditService.logEvent(
                merchantId,
                campaignId,
                "AGENT_DECISION_MADE",
                "AGENT",
                null,
                "Decision=" + decision.decision() + ", Reason: " + decision.reasoning()
        );

        // Stop Condition Check
        if (decision.stopReason() != null || "STOP_RECOVERY".equals(decision.decision())) {
            log.info("Agent Decision stopped recovery for campaign '{}'. Reason: {}", campaignId, decision.stopReason());
            record.setExecutionStatus("STOPPED");
            agentDecisionRecordRepository.save(record);
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
            record.setComplianceStatus("COMPLIANCE_BLOCKED");
            record.setExecutionStatus("BLOCKED");
            agentDecisionRecordRepository.save(record);
            return null;
        }

        if (decision.requiresHumanApproval()) {
            log.info("Agent decision requires human review for campaign '{}'. Confidence={}", campaignId, decision.confidence());
            record.setExecutionStatus("REVIEW_REQUIRED");
            agentDecisionRecordRepository.save(record);

            // Route the campaign to the merchant human-approval queue. The campaign is left in
            // ELIGIBLE with a COMPLIANCE_ALLOWED decision so the merchant's approval can revalidate
            // and create the ActionIntent through the standard boundary.
            try {
                String reason = decision.reasoning() != null && !decision.reasoning().isBlank()
                        ? decision.reasoning()
                        : "Supervisor consensus requires merchant human review before execution";
                humanApprovalService.createReview(merchantId, campaignId, reason);
            } catch (Exception e) {
                log.error("Failed to create human approval review for campaign '{}': {}", campaignId, e.getMessage(), e);
            }
            return null;
        }

        log.info("Compliance ALLOWED recovery action for campaign '{}'. Creating ActionIntent...", campaignId);
        ActionIntent actionIntent;
        try {
            actionIntent = actionIntentService.createActionIntentFromCompliance(merchantId, campaignId);
        } catch (ComplianceBlockedException e) {
            log.info("Action intent creation blocked by compliance exception for campaign '{}': {}", campaignId, e.getMessage());
            record.setComplianceStatus("COMPLIANCE_BLOCKED");
            record.setExecutionStatus("BLOCKED");
            agentDecisionRecordRepository.save(record);
            return null;
        }

        if (actionIntent == null) {
            log.info("No ActionIntent created for campaign '{}' (possibly NO_ACTION strategy)", campaignId);
            return null;
        }

        record.setExecutionStatus("AUTHORIZED");
        agentDecisionRecordRepository.save(record);

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

