package com.mend.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_config", indexes = {
    @Index(name = "idx_merchant_config_merchant_id", columnList = "merchant_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_merchant_id", columnNames = {"merchant_id"})
})
public class MerchantConfig {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "merchant_id", nullable = false, unique = true, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(nullable = false)
    private Integer maxAttempts = 3;

    @Column(nullable = false)
    private Integer maxContactAttempts = 3;

    @Column(nullable = false)
    private Integer contactWindowHours = 24;

    @Column(length = 50)
    private String retryStrategy;

    @Column
    private Integer escalationThreshold;

    @Column(columnDefinition = "TEXT")
    private String enabledRecoveryActions;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public MerchantConfig() {
    }

    public MerchantConfig(UUID id, UUID merchantId) {
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

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(Integer maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Integer getMaxContactAttempts() {
        return maxContactAttempts;
    }

    public void setMaxContactAttempts(Integer maxContactAttempts) {
        this.maxContactAttempts = maxContactAttempts;
    }

    public Integer getContactWindowHours() {
        return contactWindowHours;
    }

    public void setContactWindowHours(Integer contactWindowHours) {
        this.contactWindowHours = contactWindowHours;
    }

    public String getRetryStrategy() {
        return retryStrategy;
    }

    public void setRetryStrategy(String retryStrategy) {
        this.retryStrategy = retryStrategy;
    }

    public Integer getEscalationThreshold() {
        return escalationThreshold;
    }

    public void setEscalationThreshold(Integer escalationThreshold) {
        this.escalationThreshold = escalationThreshold;
    }

    public String getEnabledRecoveryActions() {
        return enabledRecoveryActions;
    }

    public void setEnabledRecoveryActions(String enabledRecoveryActions) {
        this.enabledRecoveryActions = enabledRecoveryActions;
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
