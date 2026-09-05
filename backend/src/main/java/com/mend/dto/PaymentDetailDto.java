package com.mend.dto;

import java.util.List;

public class PaymentDetailDto {
    private PaymentSummaryDto paymentSummary;
    private CampaignDto campaign;
    private List<CampaignAttemptDto> attempts;
    private List<ActionIntentDto> actionIntents;
    private List<AgentDecisionRecordDto> agentDecisions;
    private List<ComplianceDecisionDto> complianceDecisions;
    private List<AuditLogDto> auditLogs;

    public PaymentDetailDto() {}

    public PaymentDetailDto(PaymentSummaryDto paymentSummary, CampaignDto campaign,
                            List<CampaignAttemptDto> attempts, List<ActionIntentDto> actionIntents,
                            List<AgentDecisionRecordDto> agentDecisions, List<ComplianceDecisionDto> complianceDecisions,
                            List<AuditLogDto> auditLogs) {
        this.paymentSummary = paymentSummary;
        this.campaign = campaign;
        this.attempts = attempts;
        this.actionIntents = actionIntents;
        this.agentDecisions = agentDecisions;
        this.complianceDecisions = complianceDecisions;
        this.auditLogs = auditLogs;
    }

    public PaymentSummaryDto getPaymentSummary() { return paymentSummary; }
    public void setPaymentSummary(PaymentSummaryDto paymentSummary) { this.paymentSummary = paymentSummary; }

    public CampaignDto getCampaign() { return campaign; }
    public void setCampaign(CampaignDto campaign) { this.campaign = campaign; }

    public List<CampaignAttemptDto> getAttempts() { return attempts; }
    public void setAttempts(List<CampaignAttemptDto> attempts) { this.attempts = attempts; }

    public List<ActionIntentDto> getActionIntents() { return actionIntents; }
    public void setActionIntents(List<ActionIntentDto> actionIntents) { this.actionIntents = actionIntents; }

    public List<AgentDecisionRecordDto> getAgentDecisions() { return agentDecisions; }
    public void setAgentDecisions(List<AgentDecisionRecordDto> agentDecisions) { this.agentDecisions = agentDecisions; }

    public List<ComplianceDecisionDto> getComplianceDecisions() { return complianceDecisions; }
    public void setComplianceDecisions(List<ComplianceDecisionDto> complianceDecisions) { this.complianceDecisions = complianceDecisions; }

    public List<AuditLogDto> getAuditLogs() { return auditLogs; }
    public void setAuditLogs(List<AuditLogDto> auditLogs) { this.auditLogs = auditLogs; }
}
