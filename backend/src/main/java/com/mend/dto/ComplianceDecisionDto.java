package com.mend.dto;

import com.mend.domain.entity.ComplianceDecisionEntity;

import java.time.Instant;
import java.util.UUID;

public class ComplianceDecisionDto {
    private UUID id;
    private UUID campaignId;
    private UUID merchantId;
    private UUID recoveryDecisionId;
    private String strategy;
    private String status;
    private String reason;
    private String detailMessage;
    private String policyVersion;
    private Instant evaluatedAt;

    public ComplianceDecisionDto() {
    }

    public static ComplianceDecisionDto fromEntity(ComplianceDecisionEntity entity) {
        if (entity == null) return null;
        ComplianceDecisionDto dto = new ComplianceDecisionDto();
        dto.setId(entity.getId());
        dto.setCampaignId(entity.getCampaignId());
        dto.setMerchantId(entity.getMerchantId());
        dto.setRecoveryDecisionId(entity.getRecoveryDecisionId());
        dto.setStrategy(entity.getStrategy() != null ? entity.getStrategy().name() : null);
        dto.setStatus(entity.getStatus() != null ? entity.getStatus().name() : null);
        dto.setReason(entity.getReason() != null ? entity.getReason().name() : null);
        dto.setDetailMessage(entity.getDetailMessage());
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

    public UUID getRecoveryDecisionId() {
        return recoveryDecisionId;
    }

    public void setRecoveryDecisionId(UUID recoveryDecisionId) {
        this.recoveryDecisionId = recoveryDecisionId;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetailMessage() {
        return detailMessage;
    }

    public void setDetailMessage(String detailMessage) {
        this.detailMessage = detailMessage;
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
