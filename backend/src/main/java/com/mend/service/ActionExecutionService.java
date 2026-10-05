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
    private final TransactionTemplate executionTransactionTemplate;

    public ActionExecutionService(
            ActionIntentRepository actionIntentRepository,
            CampaignRepository campaignRepository,
            CampaignAttemptRepository campaignAttemptRepository,
            ComplianceDecisionRepository complianceDecisionRepository,
            CampaignLifecycleService campaignLifecycleService,
            ProviderActionDispatcher providerActionDispatcher,
            AuditService auditService,
            PlatformTransactionManager transactionManager) {
        this.actionIntentRepository = actionIntentRepository;
        this.campaignRepository = campaignRepository;
        this.campaignAttemptRepository = campaignAttemptRepository;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.providerActionDispatcher = providerActionDispatcher;
        this.auditService = auditService;
        this.executionTransactionTemplate = new TransactionTemplate(transactionManager);
        this.executionTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PaymentExecutionResult executeActionIntent(UUID intentId, String workerId) {
        Objects.requireNonNull(intentId, "intentId must not be null");

        // Step 1: Pre-execution validation & transaction state preparation
        ExecutionPreparation preparation;
        try {
            preparation = inExecutionTransaction(() -> prepareExecution(intentId, workerId));
        } catch (ComplianceBlockedException e) {
            log.warn("ActionIntent '{}' execution blocked by compliance: {}", intentId, e.getMessage());
            executionTransactionTemplate.executeWithoutResult(status -> {
                actionIntentRepository.findById(intentId).ifPresent(intent -> {
                    intent.setStatus(ActionIntentStatus.FAILED);
                    intent.setCompletedAt(Instant.now());
                    actionIntentRepository.save(intent);
                });
            });
            throw e;
        }
        if (!preparation.isExecutable()) {
            log.info("ActionIntent '{}' preparation halted: {}", intentId, preparation.getReason());
            return preparation.getHaltedResult();
        }

        ActionIntent intent = preparation.getIntent();
        Campaign campaign = preparation.getCampaign();

        // Step 2: Dispatch to the provider OUTSIDE any database transaction (no retry of state-changing calls)
        ProviderActionOutcome outcome;
        try {
            log.info("Dispatching ActionIntent '{}' to provider (key='{}')", intent.getId(), intent.getIdempotencyKey());
            outcome = providerActionDispatcher.dispatch(intent, campaign);
        } catch (Exception e) {
            log.error("Unhandled exception while dispatching ActionIntent '{}': {}", intent.getId(), e.getClass().getSimpleName());
            outcome = new ProviderActionOutcome.Unknown("UNEXPECTED_DISPATCH_EXCEPTION", null);
        }

        // Step 3: Transactional persistence of the outcome & state synchronization
        ProviderActionOutcome providerOutcome = outcome;
        return inExecutionTransaction(() -> finalizeExecution(intent.getId(), workerId, providerOutcome));
    }

    private ExecutionPreparation prepareExecution(UUID intentId, String workerId) {
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
            intent.setStatus(ActionIntentStatus.EXPIRED);
            actionIntentRepository.save(intent);
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

        return ExecutionPreparation.proceed(intent, campaign);
    }

    /**
     * Maps a provider outcome to intent/campaign state (M1 design doc, "Executor mapping"). Only a synthetic mock
     * capture reaches RECOVERED here; a Razorpay request outcome never does — recovery comes from captured evidence.
     */
    private PaymentExecutionResult finalizeExecution(UUID intentId, String workerId, ProviderActionOutcome outcome) {
        ActionIntent intent = actionIntentRepository.findById(intentId)
                .orElseThrow(() -> new ResourceNotFoundException("ActionIntent not found with ID: " + intentId));

        Campaign campaign = campaignRepository.findById(intent.getCampaignId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + intent.getCampaignId()));

        PaymentExecutionResult report = toReport(outcome, intent.getIdempotencyKey());

        // Check if intent was already finalized by a concurrent worker
        if (intent.getStatus().isTerminal()) {
            log.warn("ActionIntent '{}' already reached terminal state '{}'. Skipping duplicate finalization.", intentId, intent.getStatus());
            return report;
        }

        Instant now = Instant.now();
        String attemptKey = ProviderAttemptKey.forIntent(intent.getId()).value();

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

        // Step 2: Handle Outcome Specific Synchronization
        switch (outcome) {
            case ProviderActionOutcome.SyntheticCaptured captured -> {
                intent.setStatus(ActionIntentStatus.SUCCEEDED);
                intent.setResponseReference(captured.reference());
                intent.setCompletedAt(now);
                actionIntentRepository.save(intent);

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
                // A link is not money: intent waits for captured evidence (reconciliation), campaign stays EXECUTING.
                intent.setStatus(ActionIntentStatus.EXECUTING);
                intent.setResponseReference(issued.link().providerLinkId());
                actionIntentRepository.save(intent);

                logProviderAudit(intent, "PROVIDER_REQUEST_ACCEPTED", attemptKey,
                        details("providerReference", issued.link().providerLinkId()));
                log.info("ActionIntent '{}' payment link issued ({}). Awaiting payment evidence.", intentId, issued.link().providerLinkId());
            }
            case ProviderActionOutcome.Unknown unknown -> {
                // The provider may have acted: never FAILED. EXECUTING is not recycled by releaseExpiredClaims (CLAIMED only).
                intent.setStatus(ActionIntentStatus.EXECUTING);
                actionIntentRepository.save(intent);

                logProviderAudit(intent, "PROVIDER_OUTCOME_UNKNOWN", attemptKey,
                        details("cause", unknown.cause(), "httpStatus", unknown.httpStatus()));
                log.warn("ActionIntent '{}' provider outcome UNKNOWN ({}). Left EXECUTING for reconciliation.", intentId, unknown.cause());
            }
            case ProviderActionOutcome.Rejected rejected -> {
                failIntentAndCampaign(intent, campaign, now, "Provider request rejected: " + rejected.kind());
                logProviderAudit(intent, "PROVIDER_REQUEST_REJECTED", attemptKey,
                        details("kind", rejected.kind().name(), "code", rejected.code(), "httpStatus", rejected.httpStatus()));
            }
            case ProviderActionOutcome.NotSupported notSupported -> {
                failIntentAndCampaign(intent, campaign, now, "Provider operation not supported: " + notSupported.reason());
                logProviderAudit(intent, "PROVIDER_REQUEST_REJECTED", attemptKey,
                        details("kind", "NOT_SUPPORTED", "code", "PROVIDER_OPERATION_NOT_SUPPORTED", "reason", notSupported.reason()));
            }
            case ProviderActionOutcome.NotSent notSent -> {
                failIntentAndCampaign(intent, campaign, now, "Provider request not sent: " + notSent.cause());
                logProviderAudit(intent, "PROVIDER_REQUEST_NOT_SENT", attemptKey,
                        details("code", "PROVIDER_UNREACHABLE", "cause", notSent.cause()));
            }
        }

        return report;
    }

    /** Existing FAILURE path: intent FAILED, campaign EXECUTING -> FAILED -> EXHAUSTED. */
    private void failIntentAndCampaign(ActionIntent intent, Campaign campaign, Instant completedAt, String reason) {
        intent.setStatus(ActionIntentStatus.FAILED);
        intent.setCompletedAt(completedAt);
        actionIntentRepository.save(intent);

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

        private ExecutionPreparation(boolean executable, ActionIntent intent, Campaign campaign, String reason, PaymentExecutionResult haltedResult) {
            this.executable = executable;
            this.intent = intent;
            this.campaign = campaign;
            this.reason = reason;
            this.haltedResult = haltedResult;
        }

        public static ExecutionPreparation proceed(ActionIntent intent, Campaign campaign) {
            return new ExecutionPreparation(true, intent, campaign, null, null);
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
