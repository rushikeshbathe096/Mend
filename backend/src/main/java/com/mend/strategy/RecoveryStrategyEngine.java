package com.mend.strategy;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
public class RecoveryStrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(RecoveryStrategyEngine.class);

    public static final String POLICY_VERSION = "v1.0";
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final BigDecimal minConfidenceThreshold;

    public RecoveryStrategyEngine(
            @Value("${mend.recovery.strategy.min-confidence:0.80}") BigDecimal minConfidenceThreshold) {
        this.minConfidenceThreshold = minConfidenceThreshold;
    }

    public RecoveryDecision evaluate(RecoveryContext context) {
        if (context == null || context.getCampaign() == null) {
            return createSafetyDefault(null, null, null, "Null recovery context or campaign provided");
        }

        Campaign campaign = context.getCampaign();
        ClassificationResult classification = context.getClassificationResult();
        MerchantConfig merchantConfig = context.getMerchantConfig();

        UUID campaignId = campaign.getId();
        UUID merchantId = campaign.getMerchantId();
        UUID classificationId = classification != null ? classification.getId() : null;

        // Rule 1: Validate Campaign State Machine Alignment (Task 11 & Task 13)
        CampaignStatus state = campaign.getCurrentState();
        if (state == CampaignStatus.RECOVERED || state == CampaignStatus.EXHAUSTED || state == CampaignStatus.CANCELLED || state == CampaignStatus.FAILED) {
            log.info("Campaign '{}' is in terminal state '{}'. Returning NO_ACTION.", campaignId, state);
            return new RecoveryDecision(
                    campaignId, merchantId, classificationId,
                    RecoveryStrategy.NO_ACTION,
                    "Campaign is in terminal state: " + state,
                    "LOW",
                    campaign.getConfidence(),
                    POLICY_VERSION,
                    Instant.now()
            );
        }

        if (state != CampaignStatus.ELIGIBLE) {
            log.warn("Campaign '{}' is in state '{}', expected ELIGIBLE. Failing safely to MANUAL_REVIEW.", campaignId, state);
            return new RecoveryDecision(
                    campaignId, merchantId, classificationId,
                    RecoveryStrategy.MANUAL_REVIEW,
                    "Campaign state '" + state + "' is not ready for strategy evaluation",
                    "LOW",
                    campaign.getConfidence(),
                    POLICY_VERSION,
                    Instant.now()
            );
        }

        // Rule 2: Validate Attempt Count Limits (Task 6 & Task 7)
        int maxAttempts = (merchantConfig != null && merchantConfig.getMaxAttempts() != null)
                ? merchantConfig.getMaxAttempts()
                : DEFAULT_MAX_ATTEMPTS;

        int currentAttemptCount = campaign.getAttemptCount() != null ? campaign.getAttemptCount() : 0;
        if (currentAttemptCount >= maxAttempts) {
            log.info("Campaign '{}' reached max attempt limit ({} >= {}). Recommending NO_ACTION.",
                    campaignId, currentAttemptCount, maxAttempts);
            return new RecoveryDecision(
                    campaignId, merchantId, classificationId,
                    RecoveryStrategy.NO_ACTION,
                    "Maximum recovery attempts reached (" + currentAttemptCount + "/" + maxAttempts + ")",
                    "LOW",
                    campaign.getConfidence(),
                    POLICY_VERSION,
                    Instant.now()
            );
        }

        // Rule 3: Validate AI Confidence Threshold (Task 5 & Task 13)
        BigDecimal confidence = classification != null ? classification.getConfidence() : campaign.getConfidence();
        if (confidence == null || confidence.compareTo(minConfidenceThreshold) < 0) {
            log.info("Campaign '{}' AI confidence ({}) below minimum threshold ({}). Recommending MANUAL_REVIEW.",
                    campaignId, confidence, minConfidenceThreshold);
            return new RecoveryDecision(
                    campaignId, merchantId, classificationId,
                    RecoveryStrategy.MANUAL_REVIEW,
                    "AI classification confidence (" + (confidence != null ? confidence : "null") +
                            ") is below minimum threshold (" + minConfidenceThreshold + ")",
                    "NORMAL",
                    confidence,
                    POLICY_VERSION,
                    Instant.now()
            );
        }

        // Rule 4: Deterministic Decision Rules Matrix based on Failure Class & Attempt History (Task 4)
        String failureClassStr = classification != null ? classification.getFailureClass() : campaign.getFailureClass();
        if (failureClassStr == null) {
            failureClassStr = "UNKNOWN";
        }

        RecoveryStrategy computedStrategy;
        String reason;
        String priority;

        switch (failureClassStr.toUpperCase()) {
            case "INSUFFICIENT_FUNDS":
                if (currentAttemptCount == 0) {
                    computedStrategy = RecoveryStrategy.RETRY_LATER;
                    reason = "Soft failure due to insufficient funds; schedule initial retry";
                    priority = "NORMAL";
                } else if (currentAttemptCount == 1) {
                    computedStrategy = RecoveryStrategy.RETRY_LATER;
                    reason = "Second attempt for insufficient funds; schedule retry";
                    priority = "NORMAL";
                } else {
                    computedStrategy = RecoveryStrategy.CUSTOMER_ACTION_REQUIRED;
                    reason = "Multiple insufficient funds failures; customer intervention required";
                    priority = "HIGH";
                }
                break;

            case "BANK_DECLINED":
                if (currentAttemptCount == 0) {
                    computedStrategy = RecoveryStrategy.RETRY_LATER;
                    reason = "Bank decline soft failure; schedule delayed retry";
                    priority = "NORMAL";
                } else {
                    computedStrategy = RecoveryStrategy.CUSTOMER_ACTION_REQUIRED;
                    reason = "Persistent bank decline; customer action required";
                    priority = "HIGH";
                }
                break;

            case "NETWORK_FAILURE":
                if (currentAttemptCount == 0) {
                    computedStrategy = RecoveryStrategy.RETRY_IMMEDIATELY;
                    reason = "Transient network failure; retry immediately";
                    priority = "HIGH";
                } else {
                    computedStrategy = RecoveryStrategy.RETRY_LATER;
                    reason = "Repeated network failure; schedule retry later";
                    priority = "NORMAL";
                }
                break;

            case "CARD_EXPIRED":
                computedStrategy = RecoveryStrategy.CUSTOMER_ACTION_REQUIRED;
                reason = "Payment method expired; customer update required";
                priority = "HIGH";
                break;

            case "AUTHENTICATION_FAILED":
                computedStrategy = RecoveryStrategy.CUSTOMER_ACTION_REQUIRED;
                reason = "Authentication or 3DS failure; customer action required";
                priority = "HIGH";
                break;

            case "LIMIT_EXCEEDED":
                computedStrategy = RecoveryStrategy.CUSTOMER_ACTION_REQUIRED;
                reason = "Card limit exceeded; customer action required";
                priority = "HIGH";
                break;

            case "UNKNOWN":
            default:
                computedStrategy = RecoveryStrategy.MANUAL_REVIEW;
                reason = "Unrecognized or unknown failure classification: '" + failureClassStr + "'";
                priority = "LOW";
                break;
        }

        // Rule 5: Respect Merchant Configuration Restrictions (Task 7)
        if (merchantConfig != null && merchantConfig.getEnabledRecoveryActions() != null && !merchantConfig.getEnabledRecoveryActions().isBlank()) {
            String enabledActions = merchantConfig.getEnabledRecoveryActions().toUpperCase();
            if (!enabledActions.contains(computedStrategy.name()) && !enabledActions.contains("ALL")) {
                log.info("Computed strategy '{}' is disabled by merchant config ('{}'). Falling back to MANUAL_REVIEW.",
                        computedStrategy, enabledActions);
                computedStrategy = RecoveryStrategy.MANUAL_REVIEW;
                reason = "Computed strategy disabled by merchant configuration: " + merchantConfig.getEnabledRecoveryActions();
                priority = "LOW";
            }
        }

        return new RecoveryDecision(
                campaignId, merchantId, classificationId,
                computedStrategy, reason, priority, confidence, POLICY_VERSION, Instant.now()
        );
    }

    private RecoveryDecision createSafetyDefault(UUID campaignId, UUID merchantId, UUID classificationId, String reason) {
        return new RecoveryDecision(
                campaignId, merchantId, classificationId,
                RecoveryStrategy.MANUAL_REVIEW,
                "Safety Default: " + reason,
                "LOW",
                BigDecimal.ZERO,
                POLICY_VERSION,
                Instant.now()
        );
    }
}
