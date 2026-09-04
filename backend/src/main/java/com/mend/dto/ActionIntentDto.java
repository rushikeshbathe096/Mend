package com.mend.dto;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;

import java.time.Instant;
import java.util.UUID;

public class ActionIntentDto {
    private UUID id;
    private UUID merchantId;
    private UUID campaignId;
    private Integer attemptNumber;
    private String actionType;
    private String sourceStrategy;
    private UUID complianceDecisionId;
    private ActionIntentStatus status;
    private String idempotencyKey;
    private String responseReference;
    private Instant scheduledAt;
    private Instant claimedAt;
    private String workerId;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant completedAt;

    public ActionIntentDto() {
    }

    public static ActionIntentDto fromEntity(ActionIntent intent) {
        if (intent == null) return null;
        ActionIntentDto dto = new ActionIntentDto();
        dto.setId(intent.getId());
        dto.setMerchantId(intent.getMerchantId());
        dto.setCampaignId(intent.getCampaignId());
        dto.setAttemptNumber(intent.getAttemptNumber());
        dto.setActionType(intent.getActionType());
        dto.setSourceStrategy(intent.getSourceStrategy());
        dto.setComplianceDecisionId(intent.getComplianceDecisionId());
        dto.setStatus(intent.getStatus());
        dto.setIdempotencyKey(intent.getIdempotencyKey());
        dto.setResponseReference(intent.getResponseReference());
        dto.setScheduledAt(intent.getScheduledAt());
        dto.setClaimedAt(intent.getClaimedAt());
        dto.setWorkerId(intent.getWorkerId());
        dto.setExpiresAt(intent.getExpiresAt());
        dto.setCreatedAt(intent.getCreatedAt());
        dto.setCompletedAt(intent.getCompletedAt());
        return dto;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
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

    public String getSourceStrategy() {
        return sourceStrategy;
    }

    public void setSourceStrategy(String sourceStrategy) {
        this.sourceStrategy = sourceStrategy;
    }

    public UUID getComplianceDecisionId() {
        return complianceDecisionId;
    }

    public void setComplianceDecisionId(UUID complianceDecisionId) {
        this.complianceDecisionId = complianceDecisionId;
    }

    public ActionIntentStatus getStatus() {
        return status;
    }

    public void setStatus(ActionIntentStatus status) {
        this.status = status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getResponseReference() {
        return responseReference;
    }

    public void setResponseReference(String responseReference) {
        this.responseReference = responseReference;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
