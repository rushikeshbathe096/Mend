package com.mend.dto;

import com.mend.domain.enums.CampaignStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CampaignTimelineDto {
    private UUID campaignId;
    private CampaignStatus currentState;
    private CampaignDto campaign;
    private ClassificationResultDto classification;
    private List<RecoveryDecisionDto> recoveryDecisions = new ArrayList<>();
    private List<ComplianceDecisionDto> complianceDecisions = new ArrayList<>();
    private List<ActionIntentDto> actionIntents = new ArrayList<>();
    private List<CampaignAttemptDto> attempts = new ArrayList<>();
    private List<AuditLogDto> auditLogs = new ArrayList<>();

    public CampaignTimelineDto() {
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(UUID campaignId) {
        this.campaignId = campaignId;
    }

    public CampaignStatus getCurrentState() {
        return currentState;
    }

    public void setCurrentState(CampaignStatus currentState) {
        this.currentState = currentState;
    }

    public CampaignDto getCampaign() {
        return campaign;
    }

    public void setCampaign(CampaignDto campaign) {
        this.campaign = campaign;
    }

    public ClassificationResultDto getClassification() {
        return classification;
    }

    public void setClassification(ClassificationResultDto classification) {
        this.classification = classification;
    }

    public List<RecoveryDecisionDto> getRecoveryDecisions() {
        return recoveryDecisions;
    }

    public void setRecoveryDecisions(List<RecoveryDecisionDto> recoveryDecisions) {
        this.recoveryDecisions = recoveryDecisions;
    }

    public List<ComplianceDecisionDto> getComplianceDecisions() {
        return complianceDecisions;
    }

    public void setComplianceDecisions(List<ComplianceDecisionDto> complianceDecisions) {
        this.complianceDecisions = complianceDecisions;
    }

    public List<ActionIntentDto> getActionIntents() {
        return actionIntents;
    }

    public void setActionIntents(List<ActionIntentDto> actionIntents) {
        this.actionIntents = actionIntents;
    }

    public List<CampaignAttemptDto> getAttempts() {
        return attempts;
    }

    public void setAttempts(List<CampaignAttemptDto> attempts) {
        this.attempts = attempts;
    }

    public List<AuditLogDto> getAuditLogs() {
        return auditLogs;
    }

    public void setAuditLogs(List<AuditLogDto> auditLogs) {
        this.auditLogs = auditLogs;
    }
}
