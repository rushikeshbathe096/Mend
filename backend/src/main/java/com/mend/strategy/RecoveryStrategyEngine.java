package com.mend.strategy;

import com.mend.domain.entity.Campaign;
import com.mend.taxonomy.PaymentFailureClass;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class RecoveryStrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(RecoveryStrategyEngine.class);

    /** v2.0 (M3 U2.2): rules keyed by the deterministic taxonomy class; no confidence rule (ADR-003 §4). */
    public static final String POLICY_VERSION = "v2.0";
    public static final int DEFAULT_MAX_ATTEMPTS = 3;


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
                    null,
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
                    null,
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
                    null,
                    POLICY_VERSION,
                    Instant.now()
            );
        }

        // Rule 3 (M3 U2.2, ADR-003 §4): strategy by deterministic taxonomy class. Each class keeps its pre-M3 equivalent's
        // rule; a legacy (pre-M3) or UNCLASSIFIED class gets MANUAL_REVIEW (and is blocked by compliance anyway).
        // Only a deterministic row (taxonomy version) decides; a pre-M3 row or a missing classification is UNCLASSIFIED.
        String failureClassStr = classification != null ? classification.getFailureClass() : campaign.getFailureClass();
        PaymentFailureClass failureClass = classification != null && classification.getTaxonomyVersion() != null
                ? PaymentFailureClass.fromStored(failureClassStr) : null;

        // A switch expression, so the compiler checks that every taxonomy class has a rule.
        StrategyRule rule = switch (failureClass == null ? PaymentFailureClass.UNCLASSIFIED : failureClass) {
            case INSUFFICIENT_FUNDS -> currentAttemptCount == 0
                    ? new StrategyRule(RecoveryStrategy.RETRY_LATER, "Soft failure due to insufficient funds; schedule initial retry", "NORMAL")
                    : currentAttemptCount == 1
                    ? new StrategyRule(RecoveryStrategy.RETRY_LATER, "Second attempt for insufficient funds; schedule retry", "NORMAL")
                    : new StrategyRule(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, "Multiple insufficient funds failures; customer intervention required", "HIGH");
            case BANK_TECHNICAL, GATEWAY_ERROR -> currentAttemptCount == 0
                    ? new StrategyRule(RecoveryStrategy.RETRY_IMMEDIATELY, "Transient bank/gateway failure; retry immediately", "HIGH")
                    : new StrategyRule(RecoveryStrategy.RETRY_LATER, "Repeated bank/gateway failure; schedule retry later", "NORMAL");
            case LIMIT_EXCEEDED -> new StrategyRule(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, "Payment limit exceeded; customer action required", "HIGH");
            case AUTHENTICATION_FAILURE -> new StrategyRule(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, "Authentication or 3DS failure; customer action required", "HIGH");
            case INSTRUMENT_INVALID -> new StrategyRule(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED,
                    "Payment instrument expired, blocked or inactive; customer update required", "HIGH");
            case CUSTOMER_DECLINED -> new StrategyRule(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED,
                    "Customer cancelled or did not complete the payment; send a payment link", "NORMAL");
            case MANDATE_INVALID -> new StrategyRule(RecoveryStrategy.CUSTOMER_ACTION_REQUIRED, "Mandate could not be set up; customer action required", "HIGH");
            case UNCLASSIFIED -> new StrategyRule(RecoveryStrategy.MANUAL_REVIEW,
                    "No documented failure classification ('" + failureClassStr + "')", "LOW");
        };
        RecoveryStrategy computedStrategy = rule.strategy();
        String reason = rule.reason();
        String priority = rule.priority();

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
                computedStrategy, reason, priority, null, POLICY_VERSION, Instant.now()
        );
    }

    private RecoveryDecision createSafetyDefault(UUID campaignId, UUID merchantId, UUID classificationId, String reason) {
        return new RecoveryDecision(
                campaignId, merchantId, classificationId,
                RecoveryStrategy.MANUAL_REVIEW,
                "Safety Default: " + reason,
                "LOW",
                null,
                POLICY_VERSION,
                Instant.now()
        );
    }

    private record StrategyRule(RecoveryStrategy strategy, String reason, String priority) {
    }
}
