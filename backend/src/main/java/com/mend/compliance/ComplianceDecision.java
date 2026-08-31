package com.mend.compliance;

import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;

import java.time.Instant;
import java.util.UUID;

public class ComplianceDecision {

    private final UUID id;
    private final UUID campaignId;
    private final UUID merchantId;
    private final UUID recoveryDecisionId;
    private final RecoveryStrategy strategy;
    private final ComplianceStatus status;
    private final ComplianceReason reason;
    private final String detailMessage;
    private final String policyVersion;
    private final Instant evaluatedAt;

    public ComplianceDecision(
            UUID campaignId,
            UUID merchantId,
            UUID recoveryDecisionId,
            RecoveryStrategy strategy,
            ComplianceStatus status,
            ComplianceReason reason,
            String detailMessage,
            String policyVersion,
            Instant evaluatedAt) {
        this(UUID.randomUUID(), campaignId, merchantId, recoveryDecisionId, strategy, status, reason, detailMessage, policyVersion, evaluatedAt);
    }

    public ComplianceDecision(
            UUID id,
            UUID campaignId,
            UUID merchantId,
            UUID recoveryDecisionId,
            RecoveryStrategy strategy,
            ComplianceStatus status,
            ComplianceReason reason,
            String detailMessage,
            String policyVersion,
            Instant evaluatedAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.campaignId = campaignId;
        this.merchantId = merchantId;
        this.recoveryDecisionId = recoveryDecisionId;
        this.strategy = strategy;
        this.status = status;
        this.reason = reason;
        this.detailMessage = detailMessage;
        this.policyVersion = policyVersion;
        this.evaluatedAt = evaluatedAt != null ? evaluatedAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public boolean isAllowed() {
        return status == ComplianceStatus.COMPLIANCE_ALLOWED;
    }

    public boolean isBlocked() {
        return status == ComplianceStatus.COMPLIANCE_BLOCKED;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getRecoveryDecisionId() {
        return recoveryDecisionId;
    }

    public RecoveryStrategy getStrategy() {
        return strategy;
    }

    public ComplianceStatus getStatus() {
        return status;
    }

    public ComplianceReason getReason() {
        return reason;
    }

    public String getDetailMessage() {
        return detailMessage;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    @Override
    public String toString() {
        return "ComplianceDecision{" +
                "id=" + id +
                ", campaignId=" + campaignId +
                ", merchantId=" + merchantId +
                ", strategy=" + strategy +
                ", status=" + status +
                ", reason=" + reason +
                ", detailMessage='" + detailMessage + '\'' +
                ", policyVersion='" + policyVersion + '\'' +
                '}';
    }
}
