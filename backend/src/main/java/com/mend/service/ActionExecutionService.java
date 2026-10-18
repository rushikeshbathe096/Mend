package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.CampaignAttempt;
import com.mend.domain.entity.ComplianceDecisionEntity;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryAttribution;
import com.mend.domain.enums.ComplianceReason;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignAttemptRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.ComplianceDecisionRepository;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.provider.ProviderAttemptKey;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.InvalidCampaignStateException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ActionExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionService.class);

    private final ActionIntentRepository actionIntentRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignAttemptRepository campaignAttemptRepository;
    private final ComplianceDecisionRepository complianceDecisionRepository;
    private final CampaignLifecycleService campaignLifecycleService;
    private final ProviderActionDispatcher providerActionDispatcher;
    private final AuditService auditService;
    private final ActionIntentTransitions transitions;
    private final java.time.Duration awaitingPaymentFirstCheck;
    private final java.time.Duration unknownOutcomeGrace;
    private final TransactionTemplate executionTransactionTemplate;
    /** M2 U9: MEND-side deferral (OD-3 cap 5) and its backoff; review items for what a human must fix or decide. */
    private final HumanApprovalService humanApprovalService;
    private final int maxDeferrals;
    private final ReconcileBackoff deferBackoff;
    private final java.time.Duration minDeferral;
    private final com.mend.provider.ProviderCapabilities capabilities;

    public ActionExecutionService(
            ActionIntentRepository actionIntentRepository,
            CampaignRepository campaignRepository,
            CampaignAttemptRepository campaignAttemptRepository,
            ComplianceDecisionRepository complianceDecisionRepository,
            CampaignLifecycleService campaignLifecycleService,
            ProviderActionDispatcher providerActionDispatcher,
            AuditService auditService,
            PlatformTransactionManager transactionManager,
            ActionIntentTransitions transitions,
            @org.springframework.beans.factory.annotation.Value("${mend.reconciler.backoff-base-seconds:30}") long backoffBaseSeconds,
            @org.springframework.beans.factory.annotation.Value("${mend.reconciler.not-found-grace-seconds:120}") long notFoundGraceSeconds,
            HumanApprovalService humanApprovalService,
            com.mend.provider.ProviderCapabilities capabilities,
            @org.springframework.beans.factory.annotation.Value("${mend.execution.max-deferrals:5}") int maxDeferrals,
            @org.springframework.beans.factory.annotation.Value("${mend.execution.defer-backoff-base-seconds:30}") long deferBackoffBaseSeconds,
            @org.springframework.beans.factory.annotation.Value("${mend.execution.defer-backoff-cap-seconds:21600}") long deferBackoffCapSeconds) {
        this.actionIntentRepository = actionIntentRepository;
        this.campaignRepository = campaignRepository;
        this.campaignAttemptRepository = campaignAttemptRepository;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.providerActionDispatcher = providerActionDispatcher;
        this.auditService = auditService;
        this.transitions = transitions;
        this.awaitingPaymentFirstCheck = java.time.Duration.ofSeconds(backoffBaseSeconds);
        this.unknownOutcomeGrace = java.time.Duration.ofSeconds(notFoundGraceSeconds);
        this.executionTransactionTemplate = new TransactionTemplate(transactionManager);
        this.executionTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.humanApprovalService = humanApprovalService;
        this.maxDeferrals = maxDeferrals;
        this.capabilities = capabilities;
        this.minDeferral = java.time.Duration.ofSeconds(deferBackoffBaseSeconds);
        this.deferBackoff = new ReconcileBackoff(java.time.Duration.ofSeconds(deferBackoffBaseSeconds),
                java.time.Duration.ofSeconds(deferBackoffCapSeconds), java.util.random.RandomGenerator.getDefault());
    }

    /**
     * Executes a claimed intent. {@code claimToken} is the token the caller received from the atomic claim; the dispatch
     * and finalize transitions only apply while the intent still carries it (M2 invariants 11, 12).
     */
    public PaymentExecutionResult executeActionIntent(UUID intentId, String workerId, String claimToken) {
        Objects.requireNonNull(intentId, "intentId must not be null");
        // Fail closed: without the claimer's own token there is no ownership fence.
        Objects.requireNonNull(claimToken, "claimToken must not be null - pass the token from the atomic claim");

        // Step 1: Pre-execution validation & transaction state preparation
        ExecutionPreparation preparation;
        try {
            preparation = inExecutionTransaction(() -> prepareExecution(intentId, workerId, claimToken));
        } catch (ComplianceBlockedException e) {
            log.warn("ActionIntent '{}' execution blocked by compliance: {}", intentId, e.getMessage());
            // Fenced by the caller's token: a stale worker cannot fail someone else's live claim.
            executionTransactionTemplate.executeWithoutResult(status -> {
                if (transitions.abandonClaim(intentId, claimToken, ActionIntentStatus.FAILED, Instant.now())) {
                    // U2.4 review (invariant 9): the CLAIMED -> FAILED transition is audited (ids and the block code only)
                    actionIntentRepository.findById(intentId).ifPresent(i -> auditService.logEvent(i.getMerchantId(), i.getCampaignId(),
                            "ACTION_COMPLIANCE_BLOCKED", "SYSTEM", null, "ActionIntent " + intentId + " blocked before dispatch: " + e.getMessage()));
                }
            });
            throw e;
        } catch (ClaimLostBeforeDispatch lost) {
            // The prepare transaction (incl. any campaign transition) rolled back; no provider call is made.
            executionTransactionTemplate.executeWithoutResult(status -> auditService.logEvent(
                    lost.merchantId, lost.campaignId, "CLAIM_LOST_BEFORE_DISPATCH", "SYSTEM", null,
                    "ActionIntent " + intentId + " claim no longer held at dispatch; provider not called"));
            log.warn("ActionIntent '{}' claim lost before dispatch; provider not called", intentId);
            return PaymentExecutionResult.failure("Claim lost before dispatch", "CLAIM_LOST_BEFORE_DISPATCH", lost.idempotencyKey);
        }
        if (!preparation.isExecutable()) {
            log.info("ActionIntent '{}' preparation halted: {}", intentId, preparation.getReason());
            return preparation.getHaltedResult();
        }

        ActionIntent intent = preparation.getIntent();
        Campaign campaign = preparation.getCampaign();
        String heldToken = preparation.getClaimToken();

        // Step 2: Dispatch to the provider OUTSIDE any database transaction (no retry of state-changing calls)
        ProviderActionOutcome outcome;
        try {
            log.info("Dispatching ActionIntent '{}' to provider (key='{}')", intent.getId(), intent.getIdempotencyKey());
            outcome = providerActionDispatcher.dispatch(intent, campaign);
        } catch (Exception e) {
            log.error("Unhandled exception while dispatching ActionIntent '{}': {}", intent.getId(), e.getClass().getSimpleName());
            log.debug("Dispatch exception detail for ActionIntent '{}'", intent.getId(), e);
            outcome = new ProviderActionOutcome.Unknown("UNEXPECTED_DISPATCH_EXCEPTION", null);
        }

        // Step 3: Transactional persistence of the outcome & state synchronization
        ProviderActionOutcome providerOutcome = outcome;
        return inExecutionTransaction(() -> finalizeExecution(intent.getId(), heldToken, providerOutcome));
    }

    private ExecutionPreparation prepareExecution(UUID intentId, String workerId, String callerClaimToken) {
        ActionIntent intent = actionIntentRepository.findById(intentId)
                .orElseThrow(() -> new ResourceNotFoundException("ActionIntent not found with ID: " + intentId));

        // 1. Claim status validation
        if (intent.getStatus() != ActionIntentStatus.CLAIMED) {
            return ExecutionPreparation.halt(intent, "ActionIntent status is not CLAIMED (" + intent.getStatus() + ")");
        }

        // 2. Worker ownership check (if workerId supplied)
        if (workerId != null && intent.getWorkerId() != null && !workerId.equals(intent.getWorkerId())) {
            return ExecutionPreparation.halt(intent, "Worker mismatch for ActionIntent. Claimed by: " + intent.getWorkerId() + ", Executed by: " + workerId);
        }

        // 3. Expiration validation
        if (intent.getExpiresAt() != null && intent.getExpiresAt().isBefore(Instant.now())) {
            if (!transitions.abandonClaim(intent.getId(), callerClaimToken, ActionIntentStatus.EXPIRED, Instant.now())) {
                return ExecutionPreparation.halt(intent, "Claim no longer held by this worker (expiry not applied)");
            }
            auditService.logEvent(
                    intent.getMerchantId(), intent.getCampaignId(),
                    "ACTION_EXPIRED", "SYSTEM", null,
                    "ActionIntent " + intent.getId() + " expired before execution."
            );
            return ExecutionPreparation.halt(intent, "ActionIntent expired at " + intent.getExpiresAt());
        }

        // 4. Campaign validation & Tenant Isolation check
        Campaign campaign = campaignRepository.findById(intent.getCampaignId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + intent.getCampaignId()));

        if (!campaign.getMerchantId().equals(intent.getMerchantId())) {
            throw new TenantAccessDeniedException("Tenant mismatch! ActionIntent merchant: " + intent.getMerchantId() + ", Campaign merchant: " + campaign.getMerchantId());
        }

        // 5. Campaign state validation
        CampaignStatus campaignState = campaign.getCurrentState();
        if (campaignState == CampaignStatus.CANCELLED || campaignState == CampaignStatus.EXHAUSTED ||
            campaignState == CampaignStatus.RECOVERED || campaignState == CampaignStatus.FAILED) {
            return ExecutionPreparation.halt(intent, "Campaign " + campaign.getId() + " is in non-executable state: " + campaignState);
        }

        // 6. Compliance Decision Gate
        if (intent.getComplianceDecisionId() != null) {
            ComplianceDecisionEntity compliance = complianceDecisionRepository.findById(intent.getComplianceDecisionId())
                    .orElseThrow(() -> new ComplianceBlockedException("Compliance decision not found: " + intent.getComplianceDecisionId()));
            if (compliance.getStatus() != ComplianceStatus.COMPLIANCE_ALLOWED) {
                throw new ComplianceBlockedException("Compliance decision is not ALLOWED: " + compliance.getReason());
            }
        } else {
            Optional<ComplianceDecisionEntity> latestCompliance = complianceDecisionRepository.findFirstByCampaignIdOrderByEvaluatedAtDesc(campaign.getId());
            if (latestCompliance.isEmpty() || latestCompliance.get().getStatus() != ComplianceStatus.COMPLIANCE_ALLOWED) {
                String reason = latestCompliance.map(c -> c.getReason().name()).orElse("MISSING_COMPLIANCE_DECISION");
                throw new ComplianceBlockedException("Compliance decision missing or not ALLOWED: " + reason);
            }
        }

        // 6b. M3 U2.4 (ADR-003 §5): contact opt-out, re-read under a share lock in this dispatch transaction. A stored
        // ALLOWED decision from before the opt-out does not authorize contacting the customer afterwards.
        if (campaignRepository.isContactOptedOutForShare(campaign.getId()).orElse(Boolean.TRUE)) {
            throw new ComplianceBlockedException("Customer opted out of contact (" + ComplianceReason.CUSTOMER_OPTED_OUT + ")");
        }

        // 7. Transition Campaign ACTION_PENDING -> EXECUTING if applicable
        if (campaign.getCurrentState() == CampaignStatus.ACTION_PENDING) {
            campaignLifecycleService.transitionState(
                    campaign.getMerchantId(),
                    campaign.getId(),
                    CampaignStatus.EXECUTING,
                    "Action execution started for ActionIntent " + intent.getId(),
                    "SYSTEM",
                    null
            );
        }

        auditService.logStructuredEvent(
                intent.getMerchantId(),
                intent.getCampaignId(),
                "ACTION_EXECUTING",
                "SYSTEM",
                null,
                "ActionIntent " + intent.getId() + " execution started by worker: " + (workerId != null ? workerId : "default"),
                Map.of("attemptNumber", intent.getAttemptNumber(), "actionType", intent.getActionType() != null ? intent.getActionType() : "UNKNOWN"),
                Map.of("idempotencyKey", intent.getIdempotencyKey() != null ? intent.getIdempotencyKey() : "")
        );

        // 8. Dispatch fence (M2): CLAIMED -> EXECUTING by the claim-token holder, in the same transaction as the
        // compliance re-check above. Not applied => another actor owns the intent: roll back and make no provider call.
        if (!transitions.dispatch(intent.getId(), callerClaimToken, Instant.now())) {
            throw new ClaimLostBeforeDispatch(intent);
        }

        // Note: the intent/campaign objects were loaded before the dispatch update; only their ids are used afterwards.
        return ExecutionPreparation.proceed(intent, campaign, callerClaimToken);
    }

    /** Thrown inside the prepare transaction to roll it back when the dispatch fence does not apply. */
    private static final class ClaimLostBeforeDispatch extends RuntimeException {
        private final UUID merchantId;
        private final UUID campaignId;
        private final String idempotencyKey;

        private ClaimLostBeforeDispatch(ActionIntent intent) {
            super("claim lost before dispatch", null, false, false);
            this.merchantId = intent.getMerchantId();
            this.campaignId = intent.getCampaignId();
            this.idempotencyKey = intent.getIdempotencyKey();
        }
    }

    /**
     * Maps a provider outcome to intent/campaign state (M1 design doc "Executor mapping", M2 §4). The intent update is
     * fenced by status EXECUTING and the worker's claim token; campaign attempts and campaign transitions are written only
     * if it applied. Only a synthetic mock capture reaches RECOVERED here — recovery otherwise comes from captured evidence.
     */
    private PaymentExecutionResult finalizeExecution(UUID intentId, String claimToken, ProviderActionOutcome outcome) {
        ActionIntent intent = actionIntentRepository.findById(intentId)
                .orElseThrow(() -> new ResourceNotFoundException("ActionIntent not found with ID: " + intentId));

        Campaign campaign;

        PaymentExecutionResult report = toReport(outcome, intent.getIdempotencyKey());

        Instant now = Instant.now();
        String attemptKey = ProviderAttemptKey.forIntent(intent.getId()).value();

        // M2 U9: a MEND-side failure where nothing reached the provider is deferred (same intent, later), up to the cap.
        // Bug sweep item 4: defer_attempts counts the earlier deferrals (each one moved its dispatch out of execution_attempts).
        OutcomeRetryPolicy retry = OutcomeRetryPolicy.classify(outcome);
        if (retry == OutcomeRetryPolicy.DEFER && outcome instanceof ProviderActionOutcome.Rejected rejected
                && rejected.kind() == com.mend.provider.RejectionKind.RATE_LIMITED && !resendSafe(intent)) {
            // A 429 answered a sent POST: resending later is only allowed where the provider dedups the attempt (invariant 5).
            retry = OutcomeRetryPolicy.REVIEW;
        }
        // Deferral needs the campaign in EXECUTING (it goes back to ACTION_PENDING, where the scheduler claims). Anything else
        // (e.g. the manual path) would leave a READY intent nobody claims: route it to review instead.
        CampaignStatus stateAtFinalize = campaignRepository.findById(intent.getCampaignId()).map(Campaign::getCurrentState).orElse(null);
        boolean deferNow = retry == OutcomeRetryPolicy.DEFER && intent.getDeferAttempts() < maxDeferrals
                && stateAtFinalize == CampaignStatus.EXECUTING;
        // Full jitter, but never sooner than the base delay: a MEND-side cause (e.g. missing credentials) needs time to fix.
        Instant deferUntil = deferNow ? now.plus(max(minDeferral, deferBackoff.delay(intent.getDeferAttempts()))) : null;

        // Step 0: fenced intent transition. Not applied => this worker no longer owns the intent: discard the result.
        boolean applied = deferNow ? transitions.deferFromExecuting(intentId, claimToken, deferUntil) : switch (outcome) {
            case ProviderActionOutcome.SyntheticCaptured captured -> transitions.finalizeFromExecuting(intentId, claimToken,
                    ActionIntentStatus.SUCCEEDED, captured.reference(), now, null);
            case ProviderActionOutcome.LinkIssued issued -> transitions.finalizeFromExecuting(intentId, claimToken,
                    ActionIntentStatus.AWAITING_PAYMENT, issued.link().providerLinkId(), null, now.plus(awaitingPaymentFirstCheck));
            case ProviderActionOutcome.Unknown unknown -> transitions.finalizeFromExecuting(intentId, claimToken,
                    ActionIntentStatus.NEEDS_RECONCILIATION, null, null, now.plus(unknownOutcomeGrace));
            case ProviderActionOutcome.Rejected rejected -> transitions.finalizeFromExecuting(intentId, claimToken,
                    ActionIntentStatus.FAILED, null, now, null);
            case ProviderActionOutcome.NotSupported notSupported -> transitions.finalizeFromExecuting(intentId, claimToken,
                    ActionIntentStatus.FAILED, null, now, null);
            case ProviderActionOutcome.NotSent notSent -> transitions.finalizeFromExecuting(intentId, claimToken,
                    ActionIntentStatus.FAILED, null, now, null);
        };
        if (!applied) {
            String currentStatus = actionIntentRepository.findById(intentId).map(i -> i.getStatus().name()).orElse("MISSING");
            logProviderAudit(intent, "STALE_WORKER_RESULT_DISCARDED", attemptKey,
                    details("outcome", outcome.getClass().getSimpleName(), "providerReference", providerReference(outcome),
                            "currentStatus", currentStatus));
            log.warn("ActionIntent '{}' result discarded: worker no longer owns the intent (current status {})", intentId, currentStatus);
            return report;
        }
        // The conditional update cleared the persistence context: reload the campaign before writing to it.
        campaign = campaignRepository.findById(intent.getCampaignId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + intent.getCampaignId()));

        // Step 1: Create & Persist CampaignAttempt (Enforcing UK uk_campaign_attempt_number).
        // M2: a SAFE_RETRY re-executes the same intent (same attempt number), so the row may already exist: update it.
        UUID campaignId = campaign.getId();
        CampaignAttempt attempt = campaignAttemptRepository.findByCampaignIdAndAttemptNumber(campaignId, intent.getAttemptNumber())
                .orElseGet(() -> new CampaignAttempt(UUID.randomUUID(), campaignId, intent.getAttemptNumber()));
        attempt.setActionType(intent.getActionType());
        attempt.setScheduledAt(intent.getScheduledAt());
        attempt.setStartedAt(intent.getClaimedAt() != null ? intent.getClaimedAt() : now);
        if (deferNow) {
            attempt.setStatus("DEFERRED");
            attempt.setFailureReason(OutcomeRetryPolicy.code(outcome));
        } else switch (outcome) {
            case ProviderActionOutcome.SyntheticCaptured captured -> {
                attempt.setStatus("SUCCESS");
                attempt.setExternalReference(captured.reference());
                attempt.setCompletedAt(now);
            }
            case ProviderActionOutcome.LinkIssued issued -> {
                attempt.setStatus("ISSUED");
                attempt.setExternalReference(issued.link().providerLinkId());
            }
            case ProviderActionOutcome.Unknown unknown -> attempt.setStatus("UNKNOWN");
            case ProviderActionOutcome.Rejected rejected -> {
                attempt.setStatus("FAILED");
                attempt.setFailureReason(rejected.reason() != null ? rejected.reason() : rejected.code());
                attempt.setCompletedAt(now);
            }
            case ProviderActionOutcome.NotSupported notSupported -> {
                attempt.setStatus("FAILED");
                attempt.setFailureReason(notSupported.reason());
                attempt.setCompletedAt(now);
            }
            case ProviderActionOutcome.NotSent notSent -> {
                attempt.setStatus("FAILED");
                attempt.setFailureReason("NOT_SENT:" + notSent.cause());
                attempt.setCompletedAt(now);
            }
        }

        try {
            campaignAttemptRepository.saveAndFlush(attempt);
        } catch (DataIntegrityViolationException e) {
            log.warn("CampaignAttempt unique constraint violation for campaignId='{}', attemptNumber='{}'. Duplicate attempt execution caught.",
                    campaign.getId(), intent.getAttemptNumber());
        }

        // Update Campaign attempt count
        campaign.setAttemptCount(intent.getAttemptNumber());
        campaign = campaignRepository.save(campaign);

        // Step 2: Handle Outcome Specific Synchronization (the intent status was already set by the fenced transition)
        if (deferNow) {
            deferCampaign(intent, campaign, outcome, deferUntil, attemptKey);
            return report;
        }
        switch (outcome) {
            case ProviderActionOutcome.SyntheticCaptured captured -> {
                // Bug sweep item 5: simulated money is attributed SYNTHETIC (refused outside mock mode), never MEND.
                campaignLifecycleService.recover(
                        campaign.getMerchantId(),
                        campaign.getId(),
                        RecoveryAttribution.SYNTHETIC,
                        "SYNTHETIC mock provider capture (Ref: " + captured.reference() + ")",
                        "SYSTEM",
                        null
                );

                auditService.logEvent(
                        intent.getMerchantId(), intent.getCampaignId(),
                        "ACTION_SUCCEEDED", "SYSTEM", null,
                        "ActionIntent " + intent.getId() + " SUCCEEDED (SYNTHETIC mock provider) with ref: " + captured.reference()
                );

                log.info("ActionIntent '{}' SUCCEEDED (SYNTHETIC). Campaign '{}' transitioned to RECOVERED.", intentId, campaign.getId());
            }
            case ProviderActionOutcome.LinkIssued issued -> {
                // A link is not money: the intent awaits captured evidence; the campaign stays EXECUTING.
                logProviderAudit(intent, "PROVIDER_REQUEST_ACCEPTED", attemptKey,
                        details("providerReference", issued.link().providerLinkId()));
                log.info("ActionIntent '{}' payment link issued ({}). AWAITING_PAYMENT.", intentId, issued.link().providerLinkId());
            }
            case ProviderActionOutcome.Unknown unknown -> {
                // The provider may have acted: never FAILED. Handed to reconciliation (provider lookups decide).
                logProviderAudit(intent, "PROVIDER_OUTCOME_UNKNOWN", attemptKey,
                        details("cause", unknown.cause(), "httpStatus", unknown.httpStatus()));
                log.warn("ActionIntent '{}' provider outcome UNKNOWN ({}). NEEDS_RECONCILIATION.", intentId, unknown.cause());
            }
            case ProviderActionOutcome.Rejected rejected -> {
                failOrRoute(intent, campaign, retry, outcome, "Provider request rejected: " + rejected.kind());
                logProviderAudit(intent, "PROVIDER_REQUEST_REJECTED", attemptKey,
                        details("kind", rejected.kind().name(), "code", rejected.code(), "httpStatus", rejected.httpStatus()));
            }
            case ProviderActionOutcome.NotSupported notSupported -> {
                failOrRoute(intent, campaign, retry, outcome, "Provider operation not supported: " + notSupported.reason());
                logProviderAudit(intent, "PROVIDER_REQUEST_REJECTED", attemptKey,
                        details("kind", "NOT_SUPPORTED", "code", "PROVIDER_OPERATION_NOT_SUPPORTED", "reason", notSupported.reason()));
            }
            case ProviderActionOutcome.NotSent notSent -> {
                failOrRoute(intent, campaign, retry, outcome, "Provider request not sent: " + notSent.cause());
                logProviderAudit(intent, "PROVIDER_REQUEST_NOT_SENT", attemptKey,
                        details("code", "PROVIDER_UNREACHABLE", "cause", notSent.cause()));
            }
        }

        return report;
    }

    private static String providerReference(ProviderActionOutcome outcome) {
        return switch (outcome) {
            case ProviderActionOutcome.LinkIssued issued -> issued.link().providerLinkId();
            case ProviderActionOutcome.SyntheticCaptured captured -> captured.reference();
            default -> null;
        };
    }

    private boolean resendSafe(ActionIntent intent) {
        try {
            return capabilities.supportsIdempotentResend(ActionType.valueOf(intent.getActionType()));
        } catch (IllegalArgumentException | NullPointerException unknownType) {
            return false;
        }
    }

    private static java.time.Duration max(java.time.Duration a, java.time.Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** M2 U9: intent already READY again; campaign EXECUTING -> ACTION_PENDING (RETRY_DEFERRED) so the scheduler picks it up. */
    private void deferCampaign(ActionIntent intent, Campaign campaign, ProviderActionOutcome outcome, Instant deferUntil, String attemptKey) {
        String code = OutcomeRetryPolicy.code(outcome);
        // What happened at the provider boundary, then what MEND decided.
        switch (outcome) {
            case ProviderActionOutcome.NotSent notSent -> logProviderAudit(intent, "PROVIDER_REQUEST_NOT_SENT", attemptKey,
                    details("code", "PROVIDER_UNREACHABLE", "cause", notSent.cause()));
            case ProviderActionOutcome.Rejected rejected -> logProviderAudit(intent, "PROVIDER_REQUEST_REJECTED", attemptKey,
                    details("kind", rejected.kind().name(), "code", rejected.code(), "httpStatus", rejected.httpStatus()));
            default -> { }
        }
        if (campaign.getCurrentState() == CampaignStatus.EXECUTING) {
            campaignLifecycleService.transitionState(campaign.getMerchantId(), campaign.getId(), CampaignStatus.ACTION_PENDING,
                    "RETRY_DEFERRED: " + code + " for ActionIntent " + intent.getId(), "SYSTEM", null);
        }
        logProviderAudit(intent, "EXECUTION_DEFERRED", attemptKey, details("code", code,
                "deferAttempts", intent.getDeferAttempts() + 1, "maxDeferrals", maxDeferrals, "scheduledAt", deferUntil.toString()));
        log.warn("ActionIntent '{}' deferred ({}), deferral {}/{}; next try at {}", intent.getId(), code,
                intent.getDeferAttempts() + 1, maxDeferrals, deferUntil);
    }

    /**
     * M2 U9: a failure that is ours (deferral cap reached) or needs a human (action not automatable) is routed to review:
     * campaign EXECUTING -> FAILED -> ELIGIBLE (approval requires ELIGIBLE). Only TERMINAL failures exhaust the campaign.
     */
    private void failOrRoute(ActionIntent intent, Campaign campaign, OutcomeRetryPolicy retry, ProviderActionOutcome outcome, String reason) {
        if (retry == OutcomeRetryPolicy.TERMINAL || retry == OutcomeRetryPolicy.NONE) {
            failCampaign(intent, campaign, reason);
            return;
        }
        String code = OutcomeRetryPolicy.code(outcome);
        String reviewCode = retry == OutcomeRetryPolicy.DEFER ? "OPERATOR_ACTION_REQUIRED:" + code : "HUMAN_DECISION_REQUIRED:" + code;
        CampaignStatus state = campaign.getCurrentState();
        if (state == CampaignStatus.EXECUTING) {
            campaignLifecycleService.transitionState(campaign.getMerchantId(), campaign.getId(), CampaignStatus.FAILED,
                    "Action execution failed: " + reason, "SYSTEM", null);
            state = CampaignStatus.FAILED;
        }
        if (state == CampaignStatus.FAILED || state == CampaignStatus.ACTION_PENDING) {
            campaignLifecycleService.transitionState(campaign.getMerchantId(), campaign.getId(), CampaignStatus.ELIGIBLE,
                    reviewCode + ": routed to human review", "SYSTEM", null);
        }
        humanApprovalService.createReview(intent.getMerchantId(), campaign.getId(), reviewCode + ": intent " + intent.getId()
                + (retry == OutcomeRetryPolicy.DEFER ? " deferred " + intent.getDeferAttempts() + " times; fix the cause, then approve"
                                                     : " cannot run automatically; human decision required"));
        auditService.logEvent(intent.getMerchantId(), campaign.getId(), "EXECUTION_ROUTED_TO_REVIEW", "SYSTEM", null,
                "intent=" + intent.getId() + " code=" + reviewCode + " execution_attempts=" + intent.getExecutionAttempts()
                        + " defer_attempts=" + intent.getDeferAttempts());
        log.warn("ActionIntent '{}' FAILED ({}). Campaign '{}' routed to review (ELIGIBLE), not exhausted.", intent.getId(), reviewCode, campaign.getId());
    }

    /** Existing FAILURE path for the campaign: EXECUTING -> FAILED -> EXHAUSTED (the intent is already FAILED). */
    private void failCampaign(ActionIntent intent, Campaign campaign, String reason) {
        campaignLifecycleService.transitionState(
                campaign.getMerchantId(),
                campaign.getId(),
                CampaignStatus.FAILED,
                "Action execution failed: " + reason,
                "SYSTEM",
                null
        );

        campaignLifecycleService.transitionState(
                campaign.getMerchantId(),
                campaign.getId(),
                CampaignStatus.EXHAUSTED,
                "Campaign attempts exhausted following failed action execution",
                "SYSTEM",
                null
        );

        log.info("ActionIntent '{}' FAILED. Campaign '{}' transitioned to FAILED/EXHAUSTED.", intent.getId(), campaign.getId());
    }

    /** One audit row per finalize. Ids and codes only: no provider text, no PII. */
    private void logProviderAudit(ActionIntent intent, String eventType, String attemptKey, Map<String, Object> details) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intentId", intent.getId().toString());
        metadata.put("attemptKey", attemptKey);
        metadata.put("actionType", intent.getActionType() != null ? intent.getActionType() : "UNKNOWN");
        metadata.putAll(details);
        auditService.logStructuredEvent(
                intent.getMerchantId(),
                intent.getCampaignId(),
                eventType,
                "SYSTEM",
                null,
                eventType + " for ActionIntent " + intent.getId() + " " + details,
                metadata,
                Map.of()
        );
    }

    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                details.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return details;
    }

    /** Executor report for callers (scheduler, controller). PENDING is not money. */
    private static PaymentExecutionResult toReport(ProviderActionOutcome outcome, String idempotencyKey) {
        return switch (outcome) {
            case ProviderActionOutcome.SyntheticCaptured captured -> PaymentExecutionResult.success(captured.reference(),
                    "SYNTHETIC mock provider capture", idempotencyKey);
            case ProviderActionOutcome.LinkIssued issued -> PaymentExecutionResult.pending(issued.link().providerLinkId(),
                    "Payment link issued; awaiting payment evidence", idempotencyKey);
            case ProviderActionOutcome.Unknown unknown -> PaymentExecutionResult.pending(null,
                    "Provider outcome unknown (" + unknown.cause() + "); awaiting reconciliation", idempotencyKey);
            case ProviderActionOutcome.Rejected rejected -> PaymentExecutionResult.failure(
                    rejected.reason() != null ? rejected.reason() : rejected.code(), rejected.code(), idempotencyKey);
            case ProviderActionOutcome.NotSupported notSupported -> PaymentExecutionResult.failure(notSupported.reason(),
                    "PROVIDER_OPERATION_NOT_SUPPORTED", idempotencyKey);
            case ProviderActionOutcome.NotSent notSent -> PaymentExecutionResult.failure("Provider request not sent: " + notSent.cause(),
                    "PROVIDER_UNREACHABLE", idempotencyKey);
        };
    }

    private <T> T inExecutionTransaction(Supplier<T> work) {
        return Objects.requireNonNull(
                executionTransactionTemplate.execute(status -> work.get()),
                "Execution transaction returned no result");
    }

    public static class ExecutionPreparation {
        private final boolean executable;
        private final ActionIntent intent;
        private final Campaign campaign;
        private final String reason;
        private final PaymentExecutionResult haltedResult;
        private final String claimToken;

        private ExecutionPreparation(boolean executable, ActionIntent intent, Campaign campaign, String reason, PaymentExecutionResult haltedResult) {
            this(executable, intent, campaign, reason, haltedResult, null);
        }

        private ExecutionPreparation(boolean executable, ActionIntent intent, Campaign campaign, String reason,
                                     PaymentExecutionResult haltedResult, String claimToken) {
            this.claimToken = claimToken;
            this.executable = executable;
            this.intent = intent;
            this.campaign = campaign;
            this.reason = reason;
            this.haltedResult = haltedResult;
        }

        public String getClaimToken() {
            return claimToken;
        }

        public static ExecutionPreparation proceed(ActionIntent intent, Campaign campaign, String claimToken) {
            return new ExecutionPreparation(true, intent, campaign, null, null, claimToken);
        }

        public static ExecutionPreparation halt(ActionIntent intent, String reason) {
            PaymentExecutionResult halted = PaymentExecutionResult.failure(reason, "EXECUTION_HALTED", intent != null ? intent.getIdempotencyKey() : "UNKNOWN");
            return new ExecutionPreparation(false, intent, null, reason, halted);
        }

        public boolean isExecutable() {
            return executable;
        }

        public ActionIntent getIntent() {
            return intent;
        }

        public Campaign getCampaign() {
            return campaign;
        }

        public String getReason() {
            return reason;
        }

        public PaymentExecutionResult getHaltedResult() {
            return haltedResult;
        }
    }
}
