package com.mend.dto.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AgentOrchestrationResponseDto(
        String agentTraceId,
        String merchantId,
        String campaignId,
        String paymentId,
        String decision,
        BigDecimal confidence,
        String riskLevel,
        String reasoningSummary,
        List<String> evidence,
        Boolean requiresHumanApproval,
        String complianceStatus,
        String nextStep,
        Map<String, Object> executionResult,
        Integer iterationCount,
        Boolean fallbackUsed,
        String stopReason
) {
    public AgentOrchestrationResponseDto(
            String agentTraceId,
            String merchantId,
            String campaignId,
            String paymentId,
            String decision,
            BigDecimal confidence,
            String riskLevel,
            String reasoningSummary,
            List<String> evidence,
            Boolean requiresHumanApproval,
            String complianceStatus,
            String nextStep,
            Map<String, Object> executionResult,
            Integer iterationCount,
            Boolean fallbackUsed) {
        this(agentTraceId, merchantId, campaignId, paymentId, decision, confidence, riskLevel,
             reasoningSummary, evidence, requiresHumanApproval, complianceStatus, nextStep,
             executionResult, iterationCount, fallbackUsed, null);
    }
}
