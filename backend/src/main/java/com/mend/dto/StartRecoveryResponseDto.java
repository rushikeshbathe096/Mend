package com.mend.dto;

import com.mend.dto.payment.PaymentExecutionResult;

import java.util.List;

public class StartRecoveryResponseDto {
    private CampaignDto campaign;
    private ActionIntentDto actionIntent;
    private PaymentExecutionResult executionResult;
    private List<AgentDecisionRecordDto> agentDecisions;
    private String complianceStatus;
    private boolean requiresHumanApproval;
    private String message;

    public StartRecoveryResponseDto() {
    }

    public StartRecoveryResponseDto(
            CampaignDto campaign,
            ActionIntentDto actionIntent,
            PaymentExecutionResult executionResult,
            List<AgentDecisionRecordDto> agentDecisions,
            String complianceStatus,
            boolean requiresHumanApproval,
            String message) {
        this.campaign = campaign;
        this.actionIntent = actionIntent;
        this.executionResult = executionResult;
        this.agentDecisions = agentDecisions;
        this.complianceStatus = complianceStatus;
        this.requiresHumanApproval = requiresHumanApproval;
        this.message = message;
    }

    public CampaignDto getCampaign() { return campaign; }
    public void setCampaign(CampaignDto campaign) { this.campaign = campaign; }

    public ActionIntentDto getActionIntent() { return actionIntent; }
    public void setActionIntent(ActionIntentDto actionIntent) { this.actionIntent = actionIntent; }

    public PaymentExecutionResult getExecutionResult() { return executionResult; }
    public void setExecutionResult(PaymentExecutionResult executionResult) { this.executionResult = executionResult; }

    public List<AgentDecisionRecordDto> getAgentDecisions() { return agentDecisions; }
    public void setAgentDecisions(List<AgentDecisionRecordDto> agentDecisions) { this.agentDecisions = agentDecisions; }

    public String getComplianceStatus() { return complianceStatus; }
    public void setComplianceStatus(String complianceStatus) { this.complianceStatus = complianceStatus; }

    public boolean isRequiresHumanApproval() { return requiresHumanApproval; }
    public void setRequiresHumanApproval(boolean requiresHumanApproval) { this.requiresHumanApproval = requiresHumanApproval; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
