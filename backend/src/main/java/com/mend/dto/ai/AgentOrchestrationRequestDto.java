package com.mend.dto.ai;

import java.util.UUID;

public record AgentOrchestrationRequestDto(
        String merchantId,
        String campaignId,
        String paymentId,
        String eventId,
        String failureCode,
        String failureReason,
        Long amountInCents,
        Integer attemptCount,
        String backendUrl
) {
    public static AgentOrchestrationRequestDto of(
            UUID merchantId,
            UUID campaignId,
            String paymentId,
            UUID eventId,
            String failureCode,
            String failureReason,
            Long amountInCents,
            Integer attemptCount) {
        return new AgentOrchestrationRequestDto(
                merchantId != null ? merchantId.toString() : null,
                campaignId != null ? campaignId.toString() : null,
                paymentId,
                eventId != null ? eventId.toString() : null,
                failureCode,
                failureReason,
                amountInCents != null ? amountInCents : 0L,
                attemptCount != null ? attemptCount : 1,
                "http://localhost:8080"
        );
    }
}
