package com.mend.dto;

public class UpdateMerchantConfigRequest {

    private Integer maxAttempts;
    private Integer maxContactAttempts;
    private Integer contactWindowHours;
    private String retryStrategy;
    private Integer escalationThreshold;
    private String enabledRecoveryActions;

    public UpdateMerchantConfigRequest() {
    }

    public UpdateMerchantConfigRequest(Integer maxAttempts, Integer contactWindowHours, String retryStrategy) {
        this.maxAttempts = maxAttempts;
        this.contactWindowHours = contactWindowHours;
        this.retryStrategy = retryStrategy;
    }

    public UpdateMerchantConfigRequest(Integer maxAttempts, Integer maxContactAttempts, Integer contactWindowHours,
                                      String retryStrategy, Integer escalationThreshold, String enabledRecoveryActions) {
        this.maxAttempts = maxAttempts;
        this.maxContactAttempts = maxContactAttempts;
        this.contactWindowHours = contactWindowHours;
        this.retryStrategy = retryStrategy;
        this.escalationThreshold = escalationThreshold;
        this.enabledRecoveryActions = enabledRecoveryActions;
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
}
