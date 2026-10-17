package com.mend.service;

import com.mend.compliance.ComplianceDecision;
import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.InvalidCampaignStateException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ActionIntentService {

    private static final Logger log = LoggerFactory.getLogger(ActionIntentService.class);

    private final CampaignRepository campaignRepository;
    private final MerchantConfigRepository merchantConfigRepository;
    private final ActionIntentRepository actionIntentRepository;
    private final ComplianceService complianceService;
    private final AuditService auditService;
    private final long defaultRetryDelayMinutes;
    private final ActionIntentTransitions actionIntentTransitions;
    /** Lazy: HumanApprovalService depends on this service (approval creates the intent). */
    private final org.springframework.beans.factory.ObjectProvider<HumanApprovalService> humanApprovalService;

    public ActionIntentService(
            CampaignRepository campaignRepository,
            MerchantConfigRepository merchantConfigRepository,
            ActionIntentRepository actionIntentRepository,
            ComplianceService complianceService,
            AuditService auditService,
            @Value("${mend.scheduler.default-retry-delay-minutes:15}") long defaultRetryDelayMinutes,
            ActionIntentTransitions actionIntentTransitions,
            org.springframework.beans.factory.ObjectProvider<HumanApprovalService> humanApprovalService) {
        this.campaignRepository = campaignRepository;
        this.merchantConfigRepository = merchantConfigRepository;
        this.actionIntentRepository = actionIntentRepository;
        this.complianceService = complianceService;
        this.auditService = auditService;
        this.defaultRetryDelayMinutes = defaultRetryDelayMinutes;
        this.actionIntentTransitions = actionIntentTransitions;
        this.humanApprovalService = humanApprovalService;
    }

    /** Intents that ended without recovering money; a new attempt may follow them. */
    private static final java.util.Set<ActionIntentStatus> ENDED = java.util.EnumSet.of(
            ActionIntentStatus.FAILED, ActionIntentStatus.CANCELLED, ActionIntentStatus.EXPIRED);

    /** True when compliance blocked only because the strategy is MANUAL_REVIEW (a human must decide, ADR-002 decision 10). */
    public static boolean isManualReviewBlock(ComplianceDecision decision) {
        return decision != null && decision.isBlocked() && decision.getStrategy() == RecoveryStrategy.MANUAL_REVIEW
                && decision.getReason() == com.mend.domain.enums.ComplianceReason.STRATEGY_NOT_SUPPORTED;
    }

    @Transactional
    public ActionIntent createActionIntentFromCompliance(UUID merchantId, UUID campaignId) {
        return createActionIntentFromCompliance(merchantId, campaignId, null);
    }

    /**
     * {@code approvedStrategyOverride}: M2 U9 / OD-1 — only {@link HumanApprovalService#approveReview} passes it, after a human
     * approved a MANUAL_REVIEW item (the executed action is then REQUEST_CUSTOMER_ACTION). Compliance is still re-run, for
     * the override strategy. Without it, a MANUAL_REVIEW strategy creates a review item and no intent (returns null).
     */
    @Transactional
    public ActionIntent createActionIntentFromCompliance(UUID merchantId, UUID campaignId, RecoveryStrategy approvedStrategyOverride) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        // Tenant Isolation Check
        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign " + campaignId + " does not belong to merchant " + merchantId);
        }

        // Campaign State Machine Alignment
        if (campaign.getCurrentState() != CampaignStatus.ELIGIBLE) {
            throw new InvalidCampaignStateException("Cannot create Action Intent for campaign in state: " + campaign.getCurrentState());
        }

        // Run Compliance Gate
        ComplianceDecision complianceDecision = complianceService.evaluateAndPersistCompliance(merchantId, campaignId, approvedStrategyOverride);
        if (approvedStrategyOverride == null && isManualReviewBlock(complianceDecision)) {
            humanApprovalService.getObject().createReview(merchantId, campaignId,
                    "MANUAL_REVIEW_REQUIRED: recovery strategy MANUAL_REVIEW needs a human decision; approval issues a payment link");
            auditService.logEvent(merchantId, campaignId, "MANUAL_REVIEW_ROUTED", "SYSTEM", null,
                    "strategy=MANUAL_REVIEW compliance_decision=" + complianceDecision.getId() + " intent=none");
            log.info("Campaign '{}' routed to human review (MANUAL_REVIEW); no Action Intent created", campaignId);
            return null;
        }
        if (complianceDecision.isBlocked()) {
            log.info("Action Intent creation blocked for campaign '{}': Compliance status {}", campaignId, complianceDecision.getReason());
            throw new ComplianceBlockedException("Compliance blocked Action Intent creation: " + complianceDecision.getReason() + " - " + complianceDecision.getDetailMessage());
        }

        RecoveryStrategy strategy = complianceDecision.getStrategy();
        ActionType actionType = ActionType.fromRecoveryStrategy(strategy);

        if (actionType == null || strategy == RecoveryStrategy.NO_ACTION) {
            log.info("No executable action type mapped for strategy '{}' on campaign '{}'", strategy, campaignId);
            return null;
        }

        int attemptNumber = campaign.currentAttemptNumber();

        // Idempotency Key Design
        String idempotencyKey = "intent:" + campaignId + ":attempt_" + attemptNumber + ":" + actionType.name();

        // Check if intent already exists (Task 6 idempotency)
        Optional<ActionIntent> existingIntent = actionIntentRepository.findByIdempotencyKey(idempotencyKey);
        // M2 U9 review: the campaign is ELIGIBLE again only after an attempt ended without recovery (deferral cap, routed
        // to review, escalation). Returning that ended intent would leave the approved campaign with nothing to run, so the
        // next attempt gets its own key (and attempt key). A live intent is still returned as is (idempotency).
        while (existingIntent.isPresent() && ENDED.contains(existingIntent.get().getStatus())) {
            attemptNumber++;
            idempotencyKey = "intent:" + campaignId + ":attempt_" + attemptNumber + ":" + actionType.name();
            existingIntent = actionIntentRepository.findByIdempotencyKey(idempotencyKey);
        }
        if (existingIntent.isPresent()) {
            log.info("Action Intent already exists for key '{}'. Returning existing intent.", idempotencyKey);
            return existingIntent.get();
        }

        // Deterministic Scheduling Policy
        Instant scheduledAt = calculateScheduledTime(merchantId, strategy);
        Instant now = Instant.now();
        ActionIntentStatus initialStatus = scheduledAt.isAfter(now) ? ActionIntentStatus.SCHEDULED : ActionIntentStatus.READY;

        ActionIntent intent = new ActionIntent(
                UUID.randomUUID(),
                merchantId,
                campaignId,
                attemptNumber,
                actionType.name(),
                strategy != null ? strategy.name() : null,
                complianceDecision.getId(),
                initialStatus,
                idempotencyKey,
                scheduledAt
        );

        // Expiration Window: 24 hours window
        intent.setExpiresAt(scheduledAt.plus(Duration.ofHours(24)));
        // M2 U6: one in-flight intent per invoice — the intent carries its campaign's invoice key.
        intent.setInvoiceKey(java.util.Objects.requireNonNull(campaign.getInvoiceKey(), "campaign invoice_key (NOT NULL since V13)"));

        try {
            intent = actionIntentRepository.saveAndFlush(intent);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent Action Intent creation detected for key '{}'. Retrieving existing...", idempotencyKey);
            return actionIntentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);
        }

        // Audit Trail
        auditService.logEvent(
                merchantId,
                campaignId,
                "ACTION_INTENT_CREATED",
                "SYSTEM",
                null,
                "Created Action Intent '" + actionType + "' scheduled at " + scheduledAt + " (Status: " + initialStatus + ")"
        );

        log.info("Successfully created Action Intent '{}' for campaign '{}' with status '{}'",
                intent.getId(), campaignId, initialStatus);

        return intent;
    }

    @Transactional
    public void cancelPendingIntentsForCampaign(UUID merchantId, UUID campaignId, String reason) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign " + campaignId + " does not belong to merchant " + merchantId);
        }

        List<ActionIntent> intents = actionIntentRepository.findByCampaignId(campaignId);
        for (ActionIntent intent : intents) {
            // M2: conditional update (PENDING/SCHEDULED/READY only), so work claimed in the meantime is never cancelled blindly.
            if (intent.getStatus().isExecutable() && actionIntentTransitions.cancelIfExecutable(intent.getId(), Instant.now())) {

                auditService.logEvent(
                        merchantId,
                        campaignId,
                        "ACTION_CANCELLED",
                        "SYSTEM",
                        null,
                        "Cancelled Action Intent '" + intent.getId() + "': " + reason
                );
                log.info("Cancelled Action Intent '{}' for campaign '{}': {}", intent.getId(), campaignId, reason);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ActionIntent> getIntentHistory(UUID merchantId, UUID campaignId, AuthenticatedUser currentUser) {
        validateTenantAccess(merchantId, currentUser);
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + campaignId));

        if (!campaign.getMerchantId().equals(merchantId)) {
            throw new TenantAccessDeniedException("Campaign does not belong to merchant: " + merchantId);
        }

        return actionIntentRepository.findByCampaignId(campaignId);
    }

    private Instant calculateScheduledTime(UUID merchantId, RecoveryStrategy strategy) {
        Instant now = Instant.now();
        if (strategy == RecoveryStrategy.RETRY_IMMEDIATELY || strategy == RecoveryStrategy.CUSTOMER_ACTION_REQUIRED) {
            return now;
        }

        if (strategy == RecoveryStrategy.RETRY_LATER) {
            long delayMinutes = defaultRetryDelayMinutes;
            Optional<MerchantConfig> configOpt = merchantConfigRepository.findByMerchantId(merchantId);
            if (configOpt.isPresent() && configOpt.get().getContactWindowHours() != null) {
                delayMinutes = configOpt.get().getContactWindowHours() * 60L;
            }
            return now.plus(Duration.ofMinutes(delayMinutes));
        }

        return now;
    }

    private void validateTenantAccess(UUID merchantId, AuthenticatedUser currentUser) {
        if (currentUser != null && !currentUser.isSystemAdmin() && !currentUser.isMemberOfMerchant(merchantId)) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId);
        }
    }
}
