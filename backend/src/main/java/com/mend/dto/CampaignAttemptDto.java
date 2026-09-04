package com.mend.dto;

import com.mend.domain.entity.CampaignAttempt;

import java.time.Instant;
import java.util.UUID;

public class CampaignAttemptDto {
    private UUID id;
    private UUID campaignId;
    private Integer attemptNumber;
    private String actionType;
    private String status;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
    private String failureReason;
    private String externalReference;
    private Instant createdAt;

    public CampaignAttemptDto() {
    }

    public static CampaignAttemptDto fromEntity(CampaignAttempt attempt) {
        if (attempt == null) return null;
        CampaignAttemptDto dto = new CampaignAttemptDto();
        dto.setId(attempt.getId());
        dto.setCampaignId(attempt.getCampaignId());
        dto.setAttemptNumber(attempt.getAttemptNumber());
        dto.setActionType(attempt.getActionType());
        dto.setStatus(attempt.getStatus());
        dto.setScheduledAt(attempt.getScheduledAt());
        dto.setStartedAt(attempt.getStartedAt());
        dto.setCompletedAt(attempt.getCompletedAt());
        dto.setFailureReason(attempt.getFailureReason());
        dto.setExternalReference(attempt.getExternalReference());
        dto.setCreatedAt(attempt.getCreatedAt());
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

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(String externalReference) {
        this.externalReference = externalReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
