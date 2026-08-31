package com.mend.domain.entity;

import com.mend.domain.enums.ActionIntentStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "action_intents", indexes = {
    @Index(name = "idx_action_intents_campaign_id", columnList = "campaign_id"),
    @Index(name = "idx_action_intents_status", columnList = "status"),
    @Index(name = "idx_action_intents_idempotency_key", columnList = "idempotency_key", unique = true)
})
public class ActionIntent {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "UUID")
    private UUID campaignId;

    @Column(nullable = false)
    private Integer attemptNumber;

    @Column(nullable = false, length = 50)
    private String actionType;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ActionIntentStatus status = ActionIntentStatus.PENDING;

    @Column(nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(length = 255)
    private String requestHash;

    @Column(length = 255)
    private String responseReference;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column
    private Instant completedAt;

    public ActionIntent() {
    }

    public ActionIntent(UUID id, UUID campaignId, Integer attemptNumber, String actionType, String idempotencyKey) {
        this.id = id;
        this.campaignId = campaignId;
        this.attemptNumber = attemptNumber;
        this.actionType = actionType;
        this.idempotencyKey = idempotencyKey;
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

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getResponseReference() {
        return responseReference;
    }

    public void setResponseReference(String responseReference) {
        this.responseReference = responseReference;
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
