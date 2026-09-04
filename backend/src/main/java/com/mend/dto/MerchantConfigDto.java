package com.mend.dto;

import com.mend.domain.entity.MerchantConfig;

import java.time.Instant;
import java.util.UUID;

public class MerchantConfigDto {

    private UUID merchantId;
    private Integer maxAttempts;
    private Integer maxContactAttempts;
    private Integer contactWindowHours;
    private String retryStrategy;
    private Integer escalationThreshold;
    private String enabledRecoveryActions;
    private Instant createdAt;
    private Instant updatedAt;

    public MerchantConfigDto() {
    }

    public MerchantConfigDto(UUID merchantId, Integer maxAttempts, Integer maxContactAttempts,
                             Integer contactWindowHours, String retryStrategy, Integer escalationThreshold,
                             String enabledRecoveryActions, Instant createdAt, Instant updatedAt) {
        this.merchantId = merchantId;
        this.maxAttempts = maxAttempts;
        this.maxContactAttempts = maxContactAttempts;
        this.contactWindowHours = contactWindowHours;
        this.retryStrategy = retryStrategy;
        this.escalationThreshold = escalationThreshold;
        this.enabledRecoveryActions = enabledRecoveryActions;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static MerchantConfigDto fromEntity(MerchantConfig config) {
        if (config == null) {
            return null;
        }
        return new MerchantConfigDto(
                config.getMerchantId(),
                config.getMaxAttempts(),
                config.getMaxContactAttempts(),
                config.getContactWindowHours(),
                config.getRetryStrategy(),
                config.getEscalationThreshold(),
                config.getEnabledRecoveryActions(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
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
