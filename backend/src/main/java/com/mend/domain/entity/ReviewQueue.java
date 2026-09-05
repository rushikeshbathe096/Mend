package com.mend.domain.entity;

import com.mend.domain.enums.ReviewQueueStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_queue", indexes = {
    @Index(name = "idx_review_queue_merchant_status", columnList = "merchant_id,status"),
    @Index(name = "idx_review_queue_status", columnList = "status"),
    @Index(name = "idx_review_queue_campaign_id", columnList = "campaign_id"),
    @Index(name = "idx_review_queue_created_at", columnList = "created_at")
})
public class ReviewQueue {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "UUID")
    private UUID campaignId;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ReviewQueueStatus status = ReviewQueueStatus.PENDING;

    @Column(name = "assigned_user_id", columnDefinition = "UUID")
    private UUID assignedUserId;

    @Column(columnDefinition = "TEXT")
    private String reviewerComment;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column
    private Instant reviewedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public ReviewQueue() {
    }

    public ReviewQueue(UUID id, UUID campaignId, UUID merchantId) {
        this.id = id;
        this.campaignId = campaignId;
        this.merchantId = merchantId;
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

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public ReviewQueueStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewQueueStatus status) {
        this.status = status;
    }

    public UUID getAssignedUserId() {
        return assignedUserId;
    }

    public void setAssignedUserId(UUID assignedUserId) {
        this.assignedUserId = assignedUserId;
    }

    public String getReviewerComment() {
        return reviewerComment;
    }

    public void setReviewerComment(String reviewerComment) {
        this.reviewerComment = reviewerComment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
