package com.mend.service;

import com.mend.compliance.ComplianceContext;
import com.mend.compliance.ComplianceDecision;
import com.mend.compliance.ComplianceEngine;
import com.mend.domain.entity.*;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.repository.*;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);

    /** Existing-action states besides {@link ActionIntentStatus#isInFlight()}: not yet claimed, or succeeded. */
    private static final Set<ActionIntentStatus> NOT_IN_FLIGHT_EXISTING_ACTION = EnumSet.of(
            ActionIntentStatus.PENDING, ActionIntentStatus.SCHEDULED, ActionIntentStatus.READY, ActionIntentStatus.SUCCEEDED);

    private final CampaignRepository campaignRepository;
    private final ClassificationResultRepository classificationResultRepository;
    private final MerchantConfigRepository merchantConfigRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final ComplianceDecisionRepository complianceDecisionRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final ComplianceEngine complianceEngine;
    private final AuditService auditService;

    public ComplianceService(
            CampaignRepository campaignRepository,
            ClassificationResultRepository classificationResultRepository,
            MerchantConfigRepository merchantConfigRepository,
            RecoveryDecisionRepository recoveryDecisionRepository,
            ComplianceDecisionRepository complianceDecisionRepository,
            ActionIntentRepository actionIntentRepository,
            ComplianceEngine complianceEngine,
            AuditService auditService) {
        this.campaignRepository = campaignRepository;
        this.classificationResultRepository = classificationResultRepository;
        this.merchantConfigRepository = merchantConfigRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.complianceEngine = complianceEngine;
        this.auditService = auditService;
    }

    @Transactional
    public ComplianceDecision evaluateAndPersistCompliance(UUID merchantId, UUID campaignId) {
        return evaluateAndPersistCompliance(merchantId, campaignId, null);
    }

    /**
     * M2 U9 (OD-1): with {@code strategyOverride} the engine evaluates that strategy instead of the campaign's recovery
     * decision (used only when a human approved a MANUAL_REVIEW item). Every other rule still runs and the decision is
     * persisted with the override strategy; the "already evaluated" shortcut applies only to a decision for that strategy.
     */
    @Transactional
    public ComplianceDecision evaluateAndPersistCompliance(UUID merchantId, UUID campaignId, RecoveryStrategy strategyOverride) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        // Tenant Isolation Check
        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign " + campaignId + " does not belong to merchant " + merchantId);
        }

        // Fetch latest Recovery Strategy Decision
        RecoveryDecisionEntity recoveryDecisionEntity = recoveryDecisionRepository
                .findFirstByCampaignIdOrderByEvaluatedAtDesc(campaignId)
                .orElse(null);

        RecoveryStrategy strategy = strategyOverride != null ? strategyOverride
                : recoveryDecisionEntity != null
                ? recoveryDecisionEntity.getStrategy()
                : (campaign.getStrategy() != null ? parseStrategy(campaign.getStrategy()) : null);

        UUID recoveryDecisionId = recoveryDecisionEntity != null ? recoveryDecisionEntity.getId() : null;

        // Idempotency Check: return existing decision if already evaluated for this recovery decision
        Optional<ComplianceDecisionEntity> existingComplianceOpt = complianceDecisionRepository
                .findFirstByCampaignIdOrderByEvaluatedAtDesc(campaignId);
        if (existingComplianceOpt.isPresent()) {
            ComplianceDecisionEntity existingEntity = existingComplianceOpt.get();
            // The shortcut reuses only a decision for the strategy being evaluated now: an OD-1 approval decision
            // (override strategy) is never handed to a path without an approval (invariant 7).
            if (recoveryDecisionId != null && java.util.Objects.equals(existingEntity.getRecoveryDecisionId(), recoveryDecisionId)
                    && strategy != null && existingEntity.getStrategy() == strategy) {
                log.info("Idempotent compliance evaluation: Returning existing ComplianceDecision for campaign '{}'", campaignId);
                return mapToDecision(existingEntity);
            }
        }

        // Fetch Classification & Merchant Config
        ClassificationResult classificationResult = classificationResultRepository
                .findLatestByCampaignId(campaignId)
                .orElse(null);

        MerchantConfig merchantConfig = merchantConfigRepository
                .findByMerchantId(merchantId)
                .orElse(null);

        // Check duplicate action protection
        boolean duplicateActionExists = checkDuplicateActionExists(campaignId, campaign.getAttemptCount());

        ComplianceContext context = new ComplianceContext(
                merchantId, campaign, strategy, classificationResult, merchantConfig, recoveryDecisionId, duplicateActionExists
        );

        // Evaluate via ComplianceEngine
        ComplianceDecision decision = complianceEngine.evaluate(context);

        UUID decisionEntityId = UUID.randomUUID();

        // Persist decision
        ComplianceDecisionEntity entity = new ComplianceDecisionEntity(
                decisionEntityId,
                decision.getCampaignId(),
                decision.getMerchantId(),
                decision.getRecoveryDecisionId(),
                decision.getStrategy() != null ? decision.getStrategy() : RecoveryStrategy.NO_ACTION,
                decision.getStatus(),
                decision.getReason(),
                decision.getDetailMessage(),
                decision.getPolicyVersion()
        );

        try {
            entity = complianceDecisionRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent compliance evaluation for campaignId='{}'. Querying latest...", campaignId);
            return complianceDecisionRepository.findFirstByCampaignIdOrderByEvaluatedAtDesc(campaignId)
                    .map(this::mapToDecision)
                    .orElse(decision);
        }

        // Emits Audit Log
        String auditEventType = decision.getStatus() == ComplianceStatus.COMPLIANCE_ALLOWED
                ? "COMPLIANCE_ALLOWED"
                : "COMPLIANCE_BLOCKED";

        auditService.logEvent(
                merchantId,
                campaignId,
                auditEventType,
                "SYSTEM",
                null,
                decision.getReason().name() + ": " + decision.getDetailMessage()
        );

        log.info("Successfully persisted compliance decision '{}' (Status: {}) for campaign '{}'",
                decision.getReason(), decision.getStatus(), campaignId);

        return mapToDecision(entity);
    }

    @Transactional(readOnly = true)
    public List<ComplianceDecisionEntity> getComplianceHistory(UUID merchantId, UUID campaignId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign does not belong to merchant: " + merchantId);
        }

        return complianceDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaignId);
    }

    private boolean checkDuplicateActionExists(UUID campaignId, Integer attemptCount) {
        if (attemptCount == null) return false;
        List<ActionIntent> intents = actionIntentRepository.findByCampaignId(campaignId);
        return intents.stream().anyMatch(intent ->
                attemptCount.equals(intent.getAttemptNumber()) && countsAsExistingAction(intent.getStatus()));
    }

    /**
     * An intent in one of these states is an active or completed action for its attempt. The in-flight states come from
     * {@link ActionIntentStatus#isInFlight()} so this list cannot drift from the one-in-flight rule (V13).
     */
    static boolean countsAsExistingAction(ActionIntentStatus status) {
        return status != null && (status.isInFlight() || NOT_IN_FLIGHT_EXISTING_ACTION.contains(status));
    }

    private RecoveryStrategy parseStrategy(String strategyStr) {
        try {
            return RecoveryStrategy.valueOf(strategyStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ComplianceDecision mapToDecision(ComplianceDecisionEntity entity) {
        return new ComplianceDecision(
                entity.getId(),
                entity.getCampaignId(),
                entity.getMerchantId(),
                entity.getRecoveryDecisionId(),
                entity.getStrategy(),
                entity.getStatus(),
                entity.getReason(),
                entity.getDetailMessage(),
                entity.getPolicyVersion(),
                entity.getEvaluatedAt()
        );
    }

    private void validateTenantAccess(UUID merchantId, AuthenticatedUser currentUser) {
        if (currentUser != null && !currentUser.isSystemAdmin() && !currentUser.isMemberOfMerchant(merchantId)) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId);
        }
    }
}
