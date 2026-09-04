package com.mend.domain.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_decision_records", indexes = {
    @Index(name = "idx_agent_decisions_campaign_id", columnList = "campaign_id"),
    @Index(name = "idx_agent_decisions_merchant_id", columnList = "merchant_id")
})
public class AgentDecisionRecord {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "UUID")
    private UUID campaignId;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(name = "payment_id", length = 100)
    private String paymentId;

    @Column(nullable = false, length = 50)
    private String decision;

    @Column(name = "selected_action", length = 50)
    private String selectedAction;

    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reasoning;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(name = "next_step", length = 50)
    private String nextStep;

    @Column(name = "stop_reason", length = 100)
    private String stopReason;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @Column(name = "requires_human_approval", nullable = false)
    private boolean requiresHumanApproval;

    @Column(name = "compliance_status", length = 50)
    private String complianceStatus;

    @Column(name = "execution_status", length = 50)
    private String executionStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AgentDecisionRecord() {
    }

    public AgentDecisionRecord(
            UUID id,
            UUID campaignId,
            UUID merchantId,
            String paymentId,
            String decision,
            String selectedAction,
            BigDecimal confidence,
            String reasoning,
            String evidence,
            String nextStep,
            String stopReason,
            String modelVersion,
            boolean requiresHumanApproval,
            String complianceStatus,
            String executionStatus) {
        this.id = id;
        this.campaignId = campaignId;
        this.merchantId = merchantId;
        this.paymentId = paymentId;
        this.decision = decision;
        this.selectedAction = selectedAction;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.evidence = evidence;
        this.nextStep = nextStep;
        this.stopReason = stopReason;
        this.modelVersion = modelVersion;
        this.requiresHumanApproval = requiresHumanApproval;
        this.complianceStatus = complianceStatus;
        this.executionStatus = executionStatus;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID campaignId) { this.campaignId = campaignId; }

    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public String getSelectedAction() { return selectedAction; }
    public void setSelectedAction(String selectedAction) { this.selectedAction = selectedAction; }

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public String getNextStep() { return nextStep; }
    public void setNextStep(String nextStep) { this.nextStep = nextStep; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public boolean isRequiresHumanApproval() { return requiresHumanApproval; }
    public void setRequiresHumanApproval(boolean requiresHumanApproval) { this.requiresHumanApproval = requiresHumanApproval; }

    public String getComplianceStatus() { return complianceStatus; }
    public void setComplianceStatus(String complianceStatus) { this.complianceStatus = complianceStatus; }

    public String getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(String executionStatus) { this.executionStatus = executionStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
