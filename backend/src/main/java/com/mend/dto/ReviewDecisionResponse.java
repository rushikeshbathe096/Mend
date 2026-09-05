package com.mend.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of a merchant human-approval decision, including the updated review
 * item and the recovery artifact created (if any) after backend revalidation.
 */
public class ReviewDecisionResponse {

    private UUID reviewId;
    private UUID campaignId;
    private String decision;
    private String message;
    private Instant decidedAt;
    private ReviewItemDto review;
    private ActionIntentDto actionIntent;
    private CampaignDto campaign;
    private List<String> validationSummary;
    private Map<String, Object> details;

    public ReviewDecisionResponse() {
    }

    public UUID getReviewId() { return reviewId; }
    public void setReviewId(UUID reviewId) { this.reviewId = reviewId; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID campaignId) { this.campaignId = campaignId; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public ReviewItemDto getReview() { return review; }
    public void setReview(ReviewItemDto review) { this.review = review; }
    public ActionIntentDto getActionIntent() { return actionIntent; }
    public void setActionIntent(ActionIntentDto actionIntent) { this.actionIntent = actionIntent; }
    public CampaignDto getCampaign() { return campaign; }
    public void setCampaign(CampaignDto campaign) { this.campaign = campaign; }
    public List<String> getValidationSummary() { return validationSummary; }
    public void setValidationSummary(List<String> validationSummary) { this.validationSummary = validationSummary; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
