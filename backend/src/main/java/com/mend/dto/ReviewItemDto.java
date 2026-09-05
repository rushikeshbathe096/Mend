package com.mend.dto;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ReviewQueue;
import com.mend.domain.enums.ReviewQueueStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Merchant-facing representation of a human-approval review item.
 * Combines the review queue record with the authoritative campaign context and
 * the structured agent decision summary (never raw chain-of-thought).
 */
public class ReviewItemDto {

    private UUID id;
    private UUID merchantId;
    private UUID campaignId;
    private String paymentId;
    private String customerIdHash;
    private String failureClass;
    private BigDecimal confidence;
    private String campaignState;
    private String strategy;
    private Integer attemptCount;
    private BigDecimal amount;

    private String reason;
    private ReviewQueueStatus status;
    private String reviewerComment;
    private UUID assignedUserId;
    private Instant createdAt;
    private Instant reviewedAt;
    private Instant expiresAt;

    // Structured agent decision summary (evidence-based, not chain-of-thought)
    private String agentDecision;
    private String agentSelectedAction;
    private BigDecimal agentConfidence;
    private String agentReasoning;
    private String agentEvidence;
    private String agentModelVersion;
    private boolean agentRequiresHumanApproval;

    public ReviewItemDto() {
    }

    public static ReviewItemDto from(ReviewQueue review, Campaign campaign, AgentDecisionRecordDto agentDecision, BigDecimal amount) {
        ReviewItemDto dto = new ReviewItemDto();
        dto.id = review.getId();
        dto.merchantId = review.getMerchantId();
        dto.campaignId = review.getCampaignId();
        dto.createdAt = review.getCreatedAt();
        dto.reviewedAt = review.getReviewedAt();
        dto.expiresAt = review.getExpiresAt();
        dto.reason = review.getReason();
        dto.status = review.getStatus();
        dto.reviewerComment = review.getReviewerComment();
        dto.assignedUserId = review.getAssignedUserId();

        if (campaign != null) {
            dto.paymentId = campaign.getPaymentId();
            dto.customerIdHash = campaign.getCustomerIdHash();
            dto.failureClass = campaign.getFailureClass();
            dto.confidence = campaign.getConfidence();
            dto.campaignState = campaign.getCurrentState() != null ? campaign.getCurrentState().name() : null;
            dto.strategy = campaign.getStrategy();
            dto.attemptCount = campaign.getAttemptCount();
        }

        if (agentDecision != null) {
            dto.agentDecision = agentDecision.getDecision();
            dto.agentSelectedAction = agentDecision.getSelectedAction();
            dto.agentConfidence = agentDecision.getConfidence();
            dto.agentReasoning = agentDecision.getReasoning();
            dto.agentEvidence = agentDecision.getEvidence();
            dto.agentModelVersion = agentDecision.getModelVersion();
            dto.agentRequiresHumanApproval = agentDecision.isRequiresHumanApproval();
        }

        dto.amount = amount;
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
    public String getCustomerIdHash() { return customerIdHash; }
    public void setCustomerIdHash(String customerIdHash) { this.customerIdHash = customerIdHash; }
    public String getFailureClass() { return failureClass; }
    public void setFailureClass(String failureClass) { this.failureClass = failureClass; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getCampaignState() { return campaignState; }
    public void setCampaignState(String campaignState) { this.campaignState = campaignState; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public ReviewQueueStatus getStatus() { return status; }
    public void setStatus(ReviewQueueStatus status) { this.status = status; }
    public String getReviewerComment() { return reviewerComment; }
    public void setReviewerComment(String reviewerComment) { this.reviewerComment = reviewerComment; }
    public UUID getAssignedUserId() { return assignedUserId; }
    public void setAssignedUserId(UUID assignedUserId) { this.assignedUserId = assignedUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getAgentDecision() { return agentDecision; }
    public void setAgentDecision(String agentDecision) { this.agentDecision = agentDecision; }
    public String getAgentSelectedAction() { return agentSelectedAction; }
    public void setAgentSelectedAction(String agentSelectedAction) { this.agentSelectedAction = agentSelectedAction; }
    public BigDecimal getAgentConfidence() { return agentConfidence; }
    public void setAgentConfidence(BigDecimal agentConfidence) { this.agentConfidence = agentConfidence; }
    public String getAgentReasoning() { return agentReasoning; }
    public void setAgentReasoning(String agentReasoning) { this.agentReasoning = agentReasoning; }
    public String getAgentEvidence() { return agentEvidence; }
    public void setAgentEvidence(String agentEvidence) { this.agentEvidence = agentEvidence; }
    public String getAgentModelVersion() { return agentModelVersion; }
    public void setAgentModelVersion(String agentModelVersion) { this.agentModelVersion = agentModelVersion; }
    public boolean isAgentRequiresHumanApproval() { return agentRequiresHumanApproval; }
    public void setAgentRequiresHumanApproval(boolean agentRequiresHumanApproval) { this.agentRequiresHumanApproval = agentRequiresHumanApproval; }
}
