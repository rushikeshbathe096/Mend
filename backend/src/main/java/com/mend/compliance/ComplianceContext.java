package com.mend.compliance;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.RecoveryStrategy;

import java.util.UUID;

public class ComplianceContext {

    private final UUID requestedMerchantId;
    private final Campaign campaign;
    private final RecoveryStrategy strategy;
    private final ClassificationResult classificationResult;
    private final MerchantConfig merchantConfig;
    private final UUID recoveryDecisionId;
    private final boolean duplicateActionExists;

    public ComplianceContext(
            UUID requestedMerchantId,
            Campaign campaign,
            RecoveryStrategy strategy,
            ClassificationResult classificationResult,
            MerchantConfig merchantConfig,
            UUID recoveryDecisionId,
            boolean duplicateActionExists) {
        this.requestedMerchantId = requestedMerchantId;
        this.campaign = campaign;
        this.strategy = strategy;
        this.classificationResult = classificationResult;
        this.merchantConfig = merchantConfig;
        this.recoveryDecisionId = recoveryDecisionId;
        this.duplicateActionExists = duplicateActionExists;
    }

    public UUID getRequestedMerchantId() {
        return requestedMerchantId;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public RecoveryStrategy getStrategy() {
        return strategy;
    }

    public ClassificationResult getClassificationResult() {
        return classificationResult;
    }

    public MerchantConfig getMerchantConfig() {
        return merchantConfig;
    }

    public UUID getRecoveryDecisionId() {
        return recoveryDecisionId;
    }

    public boolean isDuplicateActionExists() {
        return duplicateActionExists;
    }
}
