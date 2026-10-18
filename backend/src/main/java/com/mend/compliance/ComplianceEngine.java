package com.mend.compliance;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.RecoveryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class ComplianceEngine {

    private static final Logger log = LoggerFactory.getLogger(ComplianceEngine.class);

    /**
     * v2.0 (M3 U2.2): classification rule by taxonomy class; no confidence rule (ADR-003 §4).
     * v2.1 (M3 U2.4): adds the CUSTOMER_OPTED_OUT block (ADR-003 §5).
     * v2.2 (M3 follow-up, owner decision B): UNCLASSIFIED is authorized for a payment link only; a retry is blocked
     * (RETRY_NOT_ALLOWED_UNCLASSIFIED).
     */
    public static final String POLICY_VERSION = "v2.2";

    public ComplianceDecision evaluate(ComplianceContext context) {
        // Rule 1: Fail Closed & Missing Context (Task 3.7 & Task 11)
        if (context == null || context.getCampaign() == null || context.getRequestedMerchantId() == null) {
            log.warn("Compliance evaluation blocked: null context, campaign, or requested merchant ID provided");
            return createBlockedDecision(
                    context != null && context.getCampaign() != null ? context.getCampaign().getId() : null,
                    context != null ? context.getRequestedMerchantId() : null,
                    context != null ? context.getRecoveryDecisionId() : null,
                    context != null ? context.getStrategy() : null,
                    ComplianceReason.MISSING_CONTEXT,
                    "Null context, campaign, or requested merchant ID provided"
            );
        }

        Campaign campaign = context.getCampaign();
        UUID campaignId = campaign.getId();
        UUID merchantId = campaign.getMerchantId();
        UUID requestedMerchantId = context.getRequestedMerchantId();
        UUID recoveryDecisionId = context.getRecoveryDecisionId();
        RecoveryStrategy strategy = context.getStrategy();
        MerchantConfig merchantConfig = context.getMerchantConfig();
        ClassificationResult classification = context.getClassificationResult();

        // Rule 2: Tenant Isolation (Task 3.2, Task 7, Task 16)
        if (!merchantId.equals(requestedMerchantId)) {
            log.warn("Compliance evaluation blocked: tenant mismatch (campaign.merchantId={}, requested={})",
                    merchantId, requestedMerchantId);
            return createBlockedDecision(
                    campaignId, requestedMerchantId, recoveryDecisionId, strategy,
                    ComplianceReason.TENANT_MISMATCH,
                    "Campaign merchant ID (" + merchantId + ") does not match requested tenant ID (" + requestedMerchantId + ")"
            );
        }

        if (merchantConfig != null && !merchantConfig.getMerchantId().equals(merchantId)) {
            log.warn("Compliance evaluation blocked: cross-tenant config mismatch (config.merchantId={}, campaign={})",
                    merchantConfig.getMerchantId(), merchantId);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.TENANT_MISMATCH,
                    "Merchant config tenant ID (" + merchantConfig.getMerchantId() + ") does not match campaign merchant ID (" + merchantId + ")"
            );
        }

        // Rule 2b (M3 U2.4, ADR-003 §5): the customer opted out of contact. Fail closed; only a merchant admin clears it.
        if (campaign.getContactOptedOutAt() != null) {
            log.info("Compliance evaluation blocked for campaign '{}': customer opted out of contact", campaignId);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.CUSTOMER_OPTED_OUT,
                    "Customer opted out of contact at " + campaign.getContactOptedOutAt()
            );
        }

        // Rule 3: Campaign State Machine Alignment (Task 3.1, Task 3.8, Task 12)
        CampaignStatus state = campaign.getCurrentState();
        if (state != CampaignStatus.ELIGIBLE) {
            log.info("Compliance evaluation blocked for campaign '{}': state '{}' is not ELIGIBLE", campaignId, state);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.INVALID_CAMPAIGN_STATE,
                    "Campaign state '" + state + "' is invalid or non-eligible for compliance authorization"
            );
        }

        // Rule 3b (M3 U2.2, ADR-003 §4): only a documented taxonomy class can be authorized. UNCLASSIFIED, a missing class
        // and any pre-M3 (AI-era) class are blocked. This replaces both the UNKNOWN block and the confidence block: a failure
        // without provider evidence is still never authorized (invariant 7), and no confidence is consulted.
        // It runs before the strategy checks, so a legacy campaign is blocked as UNKNOWN_CLASSIFICATION and never routed to a
        // review that could not be approved. A pre-M3 row (no taxonomy version) fails closed even if its name matches.
        String failureClassStr = classification != null ? classification.getFailureClass() : campaign.getFailureClass();
        if (classification == null || !classification.deterministicClass()) {
            log.info("Compliance evaluation blocked for campaign '{}': failure class '{}' has no documented mapping",
                    campaignId, failureClassStr);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.UNKNOWN_CLASSIFICATION,
                    "Failure class '" + failureClassStr + "' has no documented mapping and cannot be authorized for recovery"
            );
        }

        // Rule 3c (M3 follow-up, owner decision B, 2026-10-18): an UNCLASSIFIED failure may get a payment link, never a
        // retry. Independent of the strategy table, so neither path alone can authorize a RETRY_PAYMENT for it.
        if (com.mend.taxonomy.PaymentFailureClass.UNCLASSIFIED.name().equals(failureClassStr)
                && (strategy == RecoveryStrategy.RETRY_IMMEDIATELY || strategy == RecoveryStrategy.RETRY_LATER)) {
            log.info("Compliance evaluation blocked for campaign '{}': retry strategy '{}' for UNCLASSIFIED", campaignId, strategy);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.RETRY_NOT_ALLOWED_UNCLASSIFIED,
                    "UNCLASSIFIED failures are recovered only by a payment link (CUSTOMER_ACTION_REQUIRED), never by a retry"
            );
        }

        // Rule 4: Strategy Support Check (Task 3.3 & Task 4)
        if (strategy == null) {
            log.warn("Compliance evaluation blocked for campaign '{}': null strategy", campaignId);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, null,
                    ComplianceReason.MISSING_CONTEXT,
                    "Recovery strategy is null"
            );
        }

        if (strategy == RecoveryStrategy.NO_ACTION) {
            log.info("Compliance evaluation blocked for campaign '{}': strategy NO_ACTION", campaignId);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.STRATEGY_NOT_SUPPORTED,
                    "NO_ACTION strategy is non-executable and cannot be authorized"
            );
        }

        if (strategy == RecoveryStrategy.MANUAL_REVIEW) {
            log.info("Compliance evaluation blocked for campaign '{}': strategy MANUAL_REVIEW", campaignId);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.STRATEGY_NOT_SUPPORTED,
                    "MANUAL_REVIEW strategy requires human manual approval; automated execution is blocked"
            );
        }

        if (strategy != RecoveryStrategy.RETRY_IMMEDIATELY &&
                strategy != RecoveryStrategy.RETRY_LATER &&
                strategy != RecoveryStrategy.CUSTOMER_ACTION_REQUIRED) {
            log.warn("Compliance evaluation blocked for campaign '{}': strategy '{}' unsupported", campaignId, strategy);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.STRATEGY_NOT_SUPPORTED,
                    "Strategy '" + strategy + "' is not supported for automated compliance authorization"
            );
        }

        // Rule 6: Attempt Limits Enforcement (Task 3.4 & Task 5)
        int maxAttempts = (merchantConfig != null && merchantConfig.getMaxAttempts() != null)
                ? merchantConfig.getMaxAttempts()
                : 3;
        int currentAttemptCount = campaign.getAttemptCount() != null ? campaign.getAttemptCount() : 0;

        if (currentAttemptCount >= maxAttempts) {
            log.info("Compliance evaluation blocked for campaign '{}': max attempts exceeded ({} >= {})",
                    campaignId, currentAttemptCount, maxAttempts);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.MAX_ATTEMPTS_EXCEEDED,
                    "Maximum attempt limit reached (" + currentAttemptCount + "/" + maxAttempts + ")"
            );
        }

        // Rule 7: Merchant Policy Verification (Task 3.10 & Task 3.11)
        if (merchantConfig != null && merchantConfig.getEnabledRecoveryActions() != null && !merchantConfig.getEnabledRecoveryActions().isBlank()) {
            String enabledActions = merchantConfig.getEnabledRecoveryActions().toUpperCase();
            if (!enabledActions.contains(strategy.name()) && !enabledActions.contains("ALL")) {
                log.info("Compliance evaluation blocked for campaign '{}': strategy '{}' disabled in merchant config",
                        campaignId, strategy);
                return createBlockedDecision(
                        campaignId, merchantId, recoveryDecisionId, strategy,
                        ComplianceReason.MERCHANT_POLICY_BLOCKED,
                        "Strategy '" + strategy + "' is disabled by merchant configuration: " + merchantConfig.getEnabledRecoveryActions()
                );
            }
        }

        // Rule 8: Duplicate / Already-Completed Action Protection (Task 3.9 & Task 6)
        if (context.isDuplicateActionExists()) {
            log.info("Compliance evaluation blocked for campaign '{}': duplicate action exists", campaignId);
            return createBlockedDecision(
                    campaignId, merchantId, recoveryDecisionId, strategy,
                    ComplianceReason.DUPLICATE_ACTION,
                    "An active or completed recovery action already exists for this campaign attempt"
            );
        }

        // All compliance safety rules passed -> ALLOWED
        log.info("Compliance evaluation ALLOWED for campaign '{}' with strategy '{}'", campaignId, strategy);
        return new ComplianceDecision(
                campaignId, merchantId, recoveryDecisionId, strategy,
                ComplianceStatus.COMPLIANCE_ALLOWED,
                ComplianceReason.ALLOWED,
                "Recovery strategy '" + strategy + "' complies with all safety policies",
                POLICY_VERSION,
                Instant.now()
        );
    }

    private ComplianceDecision createBlockedDecision(
            UUID campaignId, UUID merchantId, UUID recoveryDecisionId, RecoveryStrategy strategy,
            ComplianceReason reason, String detailMessage) {
        return new ComplianceDecision(
                campaignId, merchantId, recoveryDecisionId, strategy,
                ComplianceStatus.COMPLIANCE_BLOCKED,
                reason, detailMessage, POLICY_VERSION, Instant.now()
        );
    }
}
