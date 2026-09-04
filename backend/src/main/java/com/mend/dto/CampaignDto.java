package com.mend.dto;

import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.CampaignStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class CampaignDto {
    private UUID id;
    private UUID merchantId;
    private String paymentId;
    private String subscriptionId;
    private String customerIdHash;
    private CampaignStatus currentState;
    private String failureClass;
    private BigDecimal confidence;
    private String strategy;
    private Integer attemptCount;
    private Instant nextActionAt;
    private Instant createdAt;
    private Instant updatedAt;

    public CampaignDto() {
    }

    public static CampaignDto fromEntity(Campaign campaign) {
        if (campaign == null) return null;
        CampaignDto dto = new CampaignDto();
        dto.setId(campaign.getId());
        dto.setMerchantId(campaign.getMerchantId());
        dto.setPaymentId(campaign.getPaymentId());
        dto.setSubscriptionId(campaign.getSubscriptionId());
        dto.setCustomerIdHash(campaign.getCustomerIdHash());
        dto.setCurrentState(campaign.getCurrentState());
        dto.setFailureClass(campaign.getFailureClass());
        dto.setConfidence(campaign.getConfidence());
        dto.setStrategy(campaign.getStrategy());
        dto.setAttemptCount(campaign.getAttemptCount());
        dto.setNextActionAt(campaign.getNextActionAt());
        dto.setCreatedAt(campaign.getCreatedAt());
        dto.setUpdatedAt(campaign.getUpdatedAt());
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

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public String getCustomerIdHash() {
        return customerIdHash;
    }

    public void setCustomerIdHash(String customerIdHash) {
        this.customerIdHash = customerIdHash;
    }

    public CampaignStatus getCurrentState() {
        return currentState;
    }

    public void setCurrentState(CampaignStatus currentState) {
        this.currentState = currentState;
    }

    public String getFailureClass() {
        return failureClass;
    }

    public void setFailureClass(String failureClass) {
        this.failureClass = failureClass;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getNextActionAt() {
        return nextActionAt;
    }

    public void setNextActionAt(Instant nextActionAt) {
        this.nextActionAt = nextActionAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
