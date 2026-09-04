package com.mend.agent;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentContext(
        UUID merchantId,
        UUID campaignId,
        String paymentId,
        String subscriptionId,
        String failureClass,
        String failureCode,
        String failureReason,
        BigDecimal aiConfidence,
        int attemptCount,
        int maxAttempts,
        String campaignState,
        Long amountInCents,
        String currency,
        List<String> previousActions,
        List<String> previousOutcomes,
        String complianceStatus,
        List<String> availableActions,
        Instant timestamp
) {
    public static AgentContext build(
            Campaign campaign,
            ClassificationResult classificationResult,
            MerchantConfig merchantConfig,
            List<String> previousActions,
            List<String> previousOutcomes,
            String complianceStatus,
            List<String> availableActions) {

        int maxAtt = merchantConfig != null && merchantConfig.getMaxAttempts() != null ? merchantConfig.getMaxAttempts() : 3;
        String fClass = classificationResult != null && classificationResult.getFailureClass() != null ? classificationResult.getFailureClass() : "UNKNOWN";
        BigDecimal conf = classificationResult != null && classificationResult.getConfidence() != null ? classificationResult.getConfidence() : new BigDecimal("0.50");

        return new AgentContext(
                campaign.getMerchantId(),
                campaign.getId(),
                campaign.getPaymentId(),
                campaign.getSubscriptionId(),
                fClass,
                fClass,
                "Failure in campaign: " + campaign.getId(),
                conf,
                campaign.getAttemptCount() != null ? campaign.getAttemptCount() : 1,
                maxAtt,
                campaign.getCurrentState() != null ? campaign.getCurrentState().name() : "ELIGIBLE",
                1000L,
                "INR",
                previousActions != null ? previousActions : List.of(),
                previousOutcomes != null ? previousOutcomes : List.of(),
                complianceStatus != null ? complianceStatus : "COMPLIANCE_ALLOWED",
                availableActions != null ? availableActions : List.of("RETRY_PAYMENT", "REQUEST_CUSTOMER_ACTION", "PAUSE_SUBSCRIPTION", "OFFER_DISCOUNT"),
                Instant.now()
        );
    }
}
