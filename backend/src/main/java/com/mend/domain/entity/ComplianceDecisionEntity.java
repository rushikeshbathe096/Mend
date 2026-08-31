package com.mend.domain.entity;

import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "compliance_decisions", indexes = {
        @Index(name = "idx_compliance_decisions_campaign_id", columnList = "campaign_id"),
        @Index(name = "idx_compliance_decisions_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_compliance_decisions_status", columnList = "status")
})
public class ComplianceDecisionEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "UUID")
    private UUID campaignId;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(name = "recovery_decision_id", columnDefinition = "UUID")
    private UUID recoveryDecisionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RecoveryStrategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ComplianceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ComplianceReason reason;

    @Column(name = "detail_message", columnDefinition = "TEXT")
    private String detailMessage;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    public ComplianceDecisionEntity() {
    }

    public ComplianceDecisionEntity(
            UUID id,
            UUID campaignId,
            UUID merchantId,
            UUID recoveryDecisionId,
            RecoveryStrategy strategy,
            ComplianceStatus status,
            ComplianceReason reason,
            String detailMessage,
            String policyVersion) {
        this.id = id != null ? id : UUID.randomUUID();
        this.campaignId = campaignId;
        this.merchantId = merchantId;
        this.recoveryDecisionId = recoveryDecisionId;
        this.strategy = strategy;
        this.status = status;
        this.reason = reason;
        this.detailMessage = detailMessage;
        this.policyVersion = policyVersion;
        this.evaluatedAt = Instant.now();
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

    public RecoveryStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(RecoveryStrategy strategy) {
        this.strategy = strategy;
    }

    public ComplianceStatus getStatus() {
        return status;
    }

    public void setStatus(ComplianceStatus status) {
        this.status = status;
    }

    public ComplianceReason getReason() {
        return reason;
    }

    public void setReason(ComplianceReason reason) {
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
