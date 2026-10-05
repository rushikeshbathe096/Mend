package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.CampaignAttempt;
import com.mend.domain.entity.ComplianceDecisionEntity;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
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
            @org.springframework.beans.factory.annotation.Value("${mend.reconciler.not-found-grace-seconds:120}") long notFoundGraceSeconds) {
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
    }

    /**
     * Legacy entry point: fences with the claim token currently stored on the intent. Production paths (scheduler,
     * manual start-recovery, demo) call {@link #executeActionIntent(UUID, String, String)} with the token they claimed with,
     * which is what makes a taken-over claim detectable. Kept for existing callers in tests.
     */
    @Deprecated
    public PaymentExecutionResult executeActionIntent(UUID intentId, String workerId) {
        return executeActionIntent(intentId, workerId, null);
    }

    /**
     * Executes a claimed intent. {@code claimToken} is the token the caller received from the atomic claim; the dispatch
     * and finalize transitions only apply while the intent still carries it (M2 invariants 11, 12).
     */
    public PaymentExecutionResult executeActionIntent(UUID intentId, String workerId, String claimToken) {
        Objects.requireNonNull(intentId, "intentId must not be null");

        // Step 1: Pre-execution validation & transaction state preparation
        ExecutionPreparation preparation;
        try {
            preparation = inExecutionTransaction(() -> prepareExecution(intentId, workerId, claimToken));
        } catch (ComplianceBlockedException e) {
            log.warn("ActionIntent '{}' execution blocked by compliance: {}", intentId, e.getMessage());
            executionTransactionTemplate.executeWithoutResult(status ->
                    actionIntentRepository.findById(intentId).ifPresent(intent ->
                            transitions.abandonClaim(intentId, intent.getClaimToken(), ActionIntentStatus.FAILED, Instant.now())));
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
            transitions.abandonClaim(intent.getId(), intent.getClaimToken(), ActionIntentStatus.EXPIRED, Instant.now());
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
        String heldToken = callerClaimToken != null ? callerClaimToken : intent.getClaimToken();
        if (!transitions.dispatch(intent.getId(), heldToken, Instant.now())) {
            throw new ClaimLostBeforeDispatch(intent);
        }

        return ExecutionPreparation.proceed(intent, campaign, heldToken);
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

        Campaign campaign = campaignRepository.findById(intent.getCampaignId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + intent.getCampaignId()));

        PaymentExecutionResult report = toReport(outcome, intent.getIdempotencyKey());

        Instant now = Instant.now();
        String attemptKey = ProviderAttemptKey.forIntent(intent.getId()).value();

        // Step 0: fenced intent transition. Not applied => this worker no longer owns the intent: discard the result.
        boolean applied = switch (outcome) {
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
            logProviderAudit(intent, "STALE_WORKER_RESULT_DISCARDED", attemptKey,
                    details("outcome", outcome.getClass().getSimpleName(), "providerReference", providerReference(outcome),
                            "currentStatus", intent.getStatus().name()));
            log.warn("ActionIntent '{}' result discarded: worker no longer owns the intent (current status {})", intentId, intent.getStatus());
            return report;
        }

        // Step 1: Create & Persist CampaignAttempt (Enforcing UK uk_campaign_attempt_number)
        CampaignAttempt attempt = new CampaignAttempt(
                UUID.randomUUID(),
                campaign.getId(),
                intent.getAttemptNumber()
        );
        attempt.setActionType(intent.getActionType());
        attempt.setScheduledAt(intent.getScheduledAt());
        attempt.setStartedAt(intent.getClaimedAt() != null ? intent.getClaimedAt() : now);
        switch (outcome) {
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
        campaignRepository.save(campaign);

        // Step 2: Handle Outcome Specific Synchronization (the intent status was already set by the fenced transition)
        switch (outcome) {
            case ProviderActionOutcome.SyntheticCaptured captured -> {
                campaignLifecycleService.transitionState(
                        campaign.getMerchantId(),
                        campaign.getId(),
                        CampaignStatus.RECOVERED,
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
                failCampaign(intent, campaign, "Provider request rejected: " + rejected.kind());
                logProviderAudit(intent, "PROVIDER_REQUEST_REJECTED", attemptKey,
                        details("kind", rejected.kind().name(), "code", rejected.code(), "httpStatus", rejected.httpStatus()));
            }
            case ProviderActionOutcome.NotSupported notSupported -> {
                failCampaign(intent, campaign, "Provider operation not supported: " + notSupported.reason());
                logProviderAudit(intent, "PROVIDER_REQUEST_REJECTED", attemptKey,
                        details("kind", "NOT_SUPPORTED", "code", "PROVIDER_OPERATION_NOT_SUPPORTED", "reason", notSupported.reason()));
            }
            case ProviderActionOutcome.NotSent notSent -> {
                failCampaign(intent, campaign, "Provider request not sent: " + notSent.cause());
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
        private String claimToken;

        private ExecutionPreparation(boolean executable, ActionIntent intent, Campaign campaign, String reason, PaymentExecutionResult haltedResult) {
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
            ExecutionPreparation preparation = new ExecutionPreparation(true, intent, campaign, null, null);
            preparation.claimToken = claimToken;
            return preparation;
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
