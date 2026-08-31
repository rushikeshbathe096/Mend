package com.mend.domain.entity;

import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.RecoveryStrategy;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "action_intents", indexes = {
    @Index(name = "idx_action_intents_campaign_id", columnList = "campaign_id"),
    @Index(name = "idx_action_intents_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_action_intents_status", columnList = "status"),
    @Index(name = "idx_action_intents_scheduler_poll", columnList = "status, scheduled_at"),
    @Index(name = "idx_action_intents_idempotency_key", columnList = "idempotency_key", unique = true)
})
public class ActionIntent {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "UUID")
    private UUID campaignId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    @Column(name = "source_strategy", length = 50)
    private String sourceStrategy;

    @Column(name = "compliance_decision_id", columnDefinition = "UUID")
    private UUID complianceDecisionId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ActionIntentStatus status = ActionIntentStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 255)
    private String requestHash;

    @Column(name = "response_reference", length = 255)
    private String responseReference;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claim_token", length = 255)
    private String claimToken;

    @Column(name = "worker_id", length = 255)
    private String workerId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public ActionIntent() {
    }

    public ActionIntent(UUID id, UUID campaignId, Integer attemptNumber, String actionType, String idempotencyKey) {
        this.id = id != null ? id : UUID.randomUUID();
        this.campaignId = campaignId;
        this.attemptNumber = attemptNumber;
        this.actionType = actionType;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
        this.scheduledAt = Instant.now();
    }

    public ActionIntent(
            UUID id,
            UUID merchantId,
            UUID campaignId,
            Integer attemptNumber,
            String actionType,
            String sourceStrategy,
            UUID complianceDecisionId,
            ActionIntentStatus status,
            String idempotencyKey,
            Instant scheduledAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.merchantId = merchantId;
        this.campaignId = campaignId;
        this.attemptNumber = attemptNumber;
        this.actionType = actionType;
        this.sourceStrategy = sourceStrategy;
        this.complianceDecisionId = complianceDecisionId;
        this.status = status != null ? status : ActionIntentStatus.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.scheduledAt = scheduledAt != null ? scheduledAt : Instant.now();
        this.createdAt = Instant.now();
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

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
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
