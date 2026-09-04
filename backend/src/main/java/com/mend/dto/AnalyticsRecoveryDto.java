package com.mend.dto;

import java.util.HashMap;
import java.util.Map;

public class AnalyticsRecoveryDto {
    private Map<String, Long> strategyBreakdown = new HashMap<>();
    private Map<String, Long> failureClassBreakdown = new HashMap<>();
    private Map<String, Long> statusBreakdown = new HashMap<>();
    private Map<String, Double> recoveryRateByStrategy = new HashMap<>();
    private Map<String, Double> recoveryRateByFailureClass = new HashMap<>();
    private Map<String, Long> actionTypeBreakdown = new HashMap<>();
    private Map<String, Long> providerOutcomes = new HashMap<>();
    private Map<String, Object> aiConfidenceMetrics = new HashMap<>();
    private Map<String, Long> complianceMetrics = new HashMap<>();
    private Map<String, Object> retryMetrics = new HashMap<>();

    public AnalyticsRecoveryDto() {
    }

    public AnalyticsRecoveryDto(
            Map<String, Long> strategyBreakdown,
            Map<String, Long> failureClassBreakdown,
            Map<String, Long> statusBreakdown,
            Map<String, Double> recoveryRateByStrategy) {
        this.strategyBreakdown = strategyBreakdown;
        this.failureClassBreakdown = failureClassBreakdown;
        this.statusBreakdown = statusBreakdown;
        this.recoveryRateByStrategy = recoveryRateByStrategy;
    }

    public Map<String, Long> getStrategyBreakdown() {
        return strategyBreakdown;
    }

    public void setStrategyBreakdown(Map<String, Long> strategyBreakdown) {
        this.strategyBreakdown = strategyBreakdown;
    }

    public Map<String, Long> getFailureClassBreakdown() {
        return failureClassBreakdown;
    }

    public void setFailureClassBreakdown(Map<String, Long> failureClassBreakdown) {
        this.failureClassBreakdown = failureClassBreakdown;
    }

    public Map<String, Long> getStatusBreakdown() {
        return statusBreakdown;
    }

    public void setStatusBreakdown(Map<String, Long> statusBreakdown) {
        this.statusBreakdown = statusBreakdown;
    }

    public Map<String, Double> getRecoveryRateByStrategy() {
        return recoveryRateByStrategy;
    }

    public void setRecoveryRateByStrategy(Map<String, Double> recoveryRateByStrategy) {
        this.recoveryRateByStrategy = recoveryRateByStrategy;
    }

    public Map<String, Double> getRecoveryRateByFailureClass() {
        return recoveryRateByFailureClass;
    }

    public void setRecoveryRateByFailureClass(Map<String, Double> recoveryRateByFailureClass) {
        this.recoveryRateByFailureClass = recoveryRateByFailureClass;
    }

    public Map<String, Long> getActionTypeBreakdown() {
        return actionTypeBreakdown;
    }

    public void setActionTypeBreakdown(Map<String, Long> actionTypeBreakdown) {
        this.actionTypeBreakdown = actionTypeBreakdown;
    }

    public Map<String, Long> getProviderOutcomes() {
        return providerOutcomes;
    }

    public void setProviderOutcomes(Map<String, Long> providerOutcomes) {
        this.providerOutcomes = providerOutcomes;
    }

    public Map<String, Object> getAiConfidenceMetrics() {
        return aiConfidenceMetrics;
    }

    public void setAiConfidenceMetrics(Map<String, Object> aiConfidenceMetrics) {
        this.aiConfidenceMetrics = aiConfidenceMetrics;
    }

    public Map<String, Long> getComplianceMetrics() {
        return complianceMetrics;
    }

    public void setComplianceMetrics(Map<String, Long> complianceMetrics) {
        this.complianceMetrics = complianceMetrics;
    }

    public Map<String, Object> getRetryMetrics() {
        return retryMetrics;
    }

    public void setRetryMetrics(Map<String, Object> retryMetrics) {
        this.retryMetrics = retryMetrics;
    }
}
