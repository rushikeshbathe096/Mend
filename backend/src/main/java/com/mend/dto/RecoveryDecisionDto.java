package com.mend.dto;

import com.mend.domain.entity.RecoveryDecisionEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class RecoveryDecisionDto {
    private UUID id;
    private UUID campaignId;
    private UUID merchantId;
    private UUID classificationResultId;
    private String strategy;
    private String reason;
    private String priority;
    private BigDecimal confidence;
    private String policyVersion;
    private Instant evaluatedAt;

    public RecoveryDecisionDto() {
    }

    public static RecoveryDecisionDto fromEntity(RecoveryDecisionEntity entity) {
        if (entity == null) return null;
        RecoveryDecisionDto dto = new RecoveryDecisionDto();
        dto.setId(entity.getId());
        dto.setCampaignId(entity.getCampaignId());
        dto.setMerchantId(entity.getMerchantId());
        dto.setClassificationResultId(entity.getClassificationResultId());
        dto.setStrategy(entity.getStrategy() != null ? entity.getStrategy().name() : null);
        dto.setReason(entity.getReason());
        dto.setPriority(entity.getPriority());
        dto.setConfidence(entity.getConfidence());
        dto.setPolicyVersion(entity.getPolicyVersion());
        dto.setEvaluatedAt(entity.getEvaluatedAt());
        return dto;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(UUID campaignId) {
        this.campaignId = campaignId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public UUID getClassificationResultId() {
        return classificationResultId;
    }

    public void setClassificationResultId(UUID classificationResultId) {
        this.classificationResultId = classificationResultId;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(Instant evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }
}
