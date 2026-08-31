package com.mend.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campaign_attempts", indexes = {
    @Index(name = "idx_campaign_attempts_campaign_id", columnList = "campaign_id"),
    @Index(name = "idx_campaign_attempts_status", columnList = "status")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_campaign_attempt_number", columnNames = {"campaign_id", "attempt_number"})
})
public class CampaignAttempt {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "UUID")
    private UUID campaignId;

    @Column(nullable = false)
    private Integer attemptNumber;

    @Column(length = 50)
    private String actionType;

    @Column(length = 20)
    private String status;

    @Column
    private Instant scheduledAt;

    @Column
    private Instant startedAt;

    @Column
    private Instant completedAt;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    @Column(length = 255)
    private String externalReference;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public CampaignAttempt() {
    }

    public CampaignAttempt(UUID id, UUID campaignId, Integer attemptNumber) {
        this.id = id;
        this.campaignId = campaignId;
        this.attemptNumber = attemptNumber;
        this.createdAt = Instant.now();
    }

    // Getters and setters
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
