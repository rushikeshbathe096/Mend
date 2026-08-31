package com.mend.domain.entity;

import com.mend.domain.enums.RecoveryStrategy;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_decisions", indexes = {
    @Index(name = "idx_recovery_decisions_campaign_id", columnList = "campaign_id"),
    @Index(name = "idx_recovery_decisions_merchant_id", columnList = "merchant_id")
})
public class RecoveryDecisionEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "UUID")
    private UUID campaignId;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(name = "classification_result_id", columnDefinition = "UUID")
    private UUID classificationResultId;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private RecoveryStrategy strategy;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, length = 20)
    private String priority;

    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    public RecoveryDecisionEntity() {
    }

    public RecoveryDecisionEntity(
            UUID id,
            UUID campaignId,
            UUID merchantId,
            UUID classificationResultId,
            RecoveryStrategy strategy,
            String reason,
            String priority,
            BigDecimal confidence,
            String policyVersion) {
        this.id = id;
        this.campaignId = campaignId;
        this.merchantId = merchantId;
        this.classificationResultId = classificationResultId;
        this.strategy = strategy;
        this.reason = reason;
        this.priority = priority;
        this.confidence = confidence;
        this.policyVersion = policyVersion;
        this.evaluatedAt = Instant.now();
    }

    // Getters and Setters
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

    public RecoveryStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(RecoveryStrategy strategy) {
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
