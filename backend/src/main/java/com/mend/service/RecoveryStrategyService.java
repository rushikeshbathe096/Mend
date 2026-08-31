package com.mend.service;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.entity.RecoveryDecisionEntity;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.ClassificationResultRepository;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.domain.repository.RecoveryDecisionRepository;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.strategy.RecoveryContext;
import com.mend.strategy.RecoveryDecision;
import com.mend.strategy.RecoveryStrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RecoveryStrategyService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryStrategyService.class);

    private final CampaignRepository campaignRepository;
    private final ClassificationResultRepository classificationResultRepository;
    private final MerchantConfigRepository merchantConfigRepository;
    private final RecoveryDecisionRepository recoveryDecisionRepository;
    private final RecoveryStrategyEngine recoveryStrategyEngine;
    private final AuditService auditService;

    public RecoveryStrategyService(
            CampaignRepository campaignRepository,
            ClassificationResultRepository classificationResultRepository,
            MerchantConfigRepository merchantConfigRepository,
            RecoveryDecisionRepository recoveryDecisionRepository,
            RecoveryStrategyEngine recoveryStrategyEngine,
            AuditService auditService) {
        this.campaignRepository = campaignRepository;
        this.classificationResultRepository = classificationResultRepository;
        this.merchantConfigRepository = merchantConfigRepository;
        this.recoveryDecisionRepository = recoveryDecisionRepository;
        this.recoveryStrategyEngine = recoveryStrategyEngine;
        this.auditService = auditService;
    }

    @Transactional
    public RecoveryDecision evaluateAndPersistStrategy(UUID merchantId, UUID campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        // Tenant Isolation Check (Task 15)
        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign " + campaignId + " does not belong to merchant " + merchantId);
        }

        // Idempotency Check (Task 10)
        Optional<RecoveryDecisionEntity> existingDecisionOpt = recoveryDecisionRepository
                .findFirstByCampaignIdOrderByEvaluatedAtDesc(campaignId);

        if (existingDecisionOpt.isPresent()) {
            RecoveryDecisionEntity existingEntity = existingDecisionOpt.get();
            // If strategy has already been evaluated for this campaign in its current state, return existing
            if (existingEntity.getStrategy() != null &&
                    existingEntity.getStrategy().name().equalsIgnoreCase(campaign.getStrategy())) {
                log.info("Idempotent strategy evaluation: Campaign '{}' already evaluated as '{}'",
                        campaignId, existingEntity.getStrategy());
                return mapToDecision(existingEntity);
            }
        }

        // Fetch context details
        ClassificationResult classificationResult = classificationResultRepository
                .findLatestByCampaignId(campaignId)
                .orElse(null);

        MerchantConfig merchantConfig = merchantConfigRepository
                .findByMerchantId(merchantId)
                .orElse(null);

        RecoveryContext context = new RecoveryContext(campaign, classificationResult, merchantConfig);
        RecoveryDecision decision = recoveryStrategyEngine.evaluate(context);

        // Persist decision (Task 9)
        RecoveryDecisionEntity entity = new RecoveryDecisionEntity(
                UUID.randomUUID(),
                decision.getCampaignId(),
                decision.getMerchantId(),
                decision.getClassificationResultId(),
                decision.getStrategy(),
                decision.getReason(),
                decision.getPriority(),
                decision.getConfidence(),
                decision.getPolicyVersion()
        );

        try {
            recoveryDecisionRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent strategy evaluation for campaignId='{}'. Querying latest...", campaignId);
            return recoveryDecisionRepository.findFirstByCampaignIdOrderByEvaluatedAtDesc(campaignId)
                    .map(this::mapToDecision)
                    .orElse(decision);
        }

        // Update campaign strategy property
        campaign.setStrategy(decision.getStrategy().name());
        campaignRepository.save(campaign);

        // Auditability (Task 16)
        auditService.logEvent(
                merchantId,
                campaignId,
                "RECOVERY_STRATEGY_DETERMINED",
                "SYSTEM",
                null,
                "Determined strategy '" + decision.getStrategy() + "': " + decision.getReason()
        );

        log.info("Successfully persisted recovery strategy decision '{}' for campaign '{}'",
                decision.getStrategy(), campaignId);

        return decision;
    }

    @Transactional(readOnly = true)
    public List<RecoveryDecisionEntity> getStrategyHistory(UUID merchantId, UUID campaignId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign does not belong to merchant: " + merchantId);
        }

        return recoveryDecisionRepository.findByCampaignIdOrderByEvaluatedAtDesc(campaignId);
    }

    private RecoveryDecision mapToDecision(RecoveryDecisionEntity entity) {
        return new RecoveryDecision(
                entity.getCampaignId(),
                entity.getMerchantId(),
                entity.getClassificationResultId(),
                entity.getStrategy(),
                entity.getReason(),
                entity.getPriority(),
                entity.getConfidence(),
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
