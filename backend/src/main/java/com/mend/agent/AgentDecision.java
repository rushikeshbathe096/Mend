package com.mend.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentDecision(
        UUID decisionId,
        UUID campaignId,
        UUID merchantId,
        String decision,
        String selectedAction,
        BigDecimal confidence,
        String reasoning,
        List<String> evidence,
        String nextStep,
        String stopReason,
        String modelVersion,
        Instant decisionTimestamp,
        boolean requiresHumanApproval,
        String complianceStatus,
        String executionStatus
) {
    public static AgentDecision create(
            UUID campaignId,
            UUID merchantId,
            String decision,
            String selectedAction,
            BigDecimal confidence,
            String reasoning,
            List<String> evidence,
            String nextStep,
            String stopReason,
            String modelVersion,
            boolean requiresHumanApproval,
            String complianceStatus) {
        return new AgentDecision(
                UUID.randomUUID(),
                campaignId,
                merchantId,
                decision,
                selectedAction,
                confidence,
                reasoning,
                evidence != null ? evidence : List.of(),
                nextStep,
                stopReason,
                modelVersion != null ? modelVersion : "v2.0-agent",
                Instant.now(),
                requiresHumanApproval,
                complianceStatus != null ? complianceStatus : "COMPLIANCE_ALLOWED",
                "PENDING"
        );
    }
}
