package com.mend.strategy;

import com.mend.domain.enums.RecoveryStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class RecoveryDecision {

    private final UUID campaignId;
    private final UUID merchantId;
    private final UUID classificationResultId;
    private final RecoveryStrategy strategy;
    private final String reason;
    private final String priority;
    private final BigDecimal confidence;
    private final String policyVersion;
    private final Instant evaluatedAt;

    public RecoveryDecision(
            UUID campaignId,
            UUID merchantId,
            UUID classificationResultId,
            RecoveryStrategy strategy,
            String reason,
            String priority,
            BigDecimal confidence,
            String policyVersion,
            Instant evaluatedAt) {
        this.campaignId = campaignId;
        this.merchantId = merchantId;
        this.classificationResultId = classificationResultId;
        this.strategy = strategy;
        this.reason = reason;
        this.priority = priority;
        this.confidence = confidence;
        this.policyVersion = policyVersion;
        this.evaluatedAt = evaluatedAt != null ? evaluatedAt : Instant.now();
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getClassificationResultId() {
        return classificationResultId;
    }

    public RecoveryStrategy getStrategy() {
        return strategy;
    }

    public String getReason() {
        return reason;
    }

    public String getPriority() {
        return priority;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    @Override
    public String toString() {
        return "RecoveryDecision{" +
                "campaignId=" + campaignId +
                ", merchantId=" + merchantId +
                ", strategy=" + strategy +
                ", reason='" + reason + '\'' +
                ", priority='" + priority + '\'' +
                ", confidence=" + confidence +
                ", policyVersion='" + policyVersion + '\'' +
                '}';
    }
}
