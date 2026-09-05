package com.mend.dto;

import com.mend.domain.entity.AgentDecisionRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class AgentDecisionRecordDto {
    private UUID id;
    private UUID merchantId;
    private UUID campaignId;
    private String paymentId;
    private String decision;
    private String selectedAction;
    private BigDecimal confidence;
    private String reasoning;
    private String evidence;
    private String nextStep;
    private String stopReason;
    private String modelVersion;
    private boolean requiresHumanApproval;
    private String complianceStatus;
    private String executionStatus;
    private Instant createdAt;

    public AgentDecisionRecordDto() {
    }

    public static AgentDecisionRecordDto fromEntity(AgentDecisionRecord record) {
        if (record == null) return null;
        AgentDecisionRecordDto dto = new AgentDecisionRecordDto();
        dto.setId(record.getId());
        dto.setMerchantId(record.getMerchantId());
        dto.setCampaignId(record.getCampaignId());
        dto.setPaymentId(record.getPaymentId());
        dto.setDecision(record.getDecision());
        dto.setSelectedAction(record.getSelectedAction());
        dto.setConfidence(record.getConfidence());
        dto.setReasoning(record.getReasoning());
        dto.setEvidence(record.getEvidence());
        dto.setNextStep(record.getNextStep());
        dto.setStopReason(record.getStopReason());
        dto.setModelVersion(record.getModelVersion());
        dto.setRequiresHumanApproval(record.isRequiresHumanApproval());
        dto.setComplianceStatus(record.getComplianceStatus());
        dto.setExecutionStatus(record.getExecutionStatus());
        dto.setCreatedAt(record.getCreatedAt());
        return dto;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }

    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID campaignId) { this.campaignId = campaignId; }

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
