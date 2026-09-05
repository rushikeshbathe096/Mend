package com.mend.dto;

import com.mend.domain.enums.CampaignStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class PaymentSummaryDto {
    private String paymentId;
    private String customerIdHash;
    private UUID merchantId;
    private String failureClass;
    private BigDecimal amount;
    private CampaignStatus currentState;
    private String strategy;
    private Integer attemptCount;
    private UUID campaignId;
    private Instant createdAt;
    private Instant updatedAt;

    public PaymentSummaryDto() {}

    public PaymentSummaryDto(String paymentId, String customerIdHash, UUID merchantId, String failureClass,
                             BigDecimal amount, CampaignStatus currentState, String strategy,
                             Integer attemptCount, UUID campaignId, Instant createdAt, Instant updatedAt) {
        this.paymentId = paymentId;
        this.customerIdHash = customerIdHash;
        this.merchantId = merchantId;
        this.failureClass = failureClass;
        this.amount = amount;
        this.currentState = currentState;
        this.strategy = strategy;
        this.attemptCount = attemptCount;
        this.campaignId = campaignId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getCustomerIdHash() { return customerIdHash; }
    public void setCustomerIdHash(String customerIdHash) { this.customerIdHash = customerIdHash; }

    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }

    public String getFailureClass() { return failureClass; }
    public void setFailureClass(String failureClass) { this.failureClass = failureClass; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public CampaignStatus getCurrentState() { return currentState; }
    public void setCurrentState(CampaignStatus currentState) { this.currentState = currentState; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }

    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID campaignId) { this.campaignId = campaignId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
