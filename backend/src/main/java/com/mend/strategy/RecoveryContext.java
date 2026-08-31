package com.mend.strategy;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;

public class RecoveryContext {

    private final Campaign campaign;
    private final ClassificationResult classificationResult;
    private final MerchantConfig merchantConfig;

    public RecoveryContext(Campaign campaign, ClassificationResult classificationResult, MerchantConfig merchantConfig) {
        this.campaign = campaign;
        this.classificationResult = classificationResult;
        this.merchantConfig = merchantConfig;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public ClassificationResult getClassificationResult() {
        return classificationResult;
    }

    public MerchantConfig getMerchantConfig() {
        return merchantConfig;
    }
}
