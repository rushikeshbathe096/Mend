package com.mend.domain.entity;

import com.mend.domain.enums.CampaignStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campaigns", indexes = {
    @Index(name = "idx_campaigns_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_campaigns_merchant_state", columnList = "merchant_id,current_state"),
    @Index(name = "idx_campaigns_next_action_at", columnList = "next_action_at"),
    @Index(name = "idx_campaigns_payment_id", columnList = "payment_id"),
    @Index(name = "idx_campaigns_created_at", columnList = "created_at")
})
public class Campaign {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(length = 255)
    private String customerIdHash;

    @Column(length = 255)
    private String paymentId;

    @Column(length = 255)
    private String subscriptionId;

    @Column(length = 50)
    private String failureClass;

    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private CampaignStatus currentState = CampaignStatus.CREATED;

    @Column(length = 100)
    private String strategy;

    @Column(nullable = false)
    private Integer attemptCount = 0;

    @Column
    private Instant nextActionAt;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Campaign() {
    }

    public Campaign(UUID id, UUID merchantId) {
        this.id = id;
        this.merchantId = merchantId;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and setters
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

    public String getCustomerIdHash() {
        return customerIdHash;
    }

    public void setCustomerIdHash(String customerIdHash) {
        this.customerIdHash = customerIdHash;
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

    public CampaignStatus getCurrentState() {
        return currentState;
    }

    public void setCurrentState(CampaignStatus currentState) {
        this.currentState = currentState;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
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
