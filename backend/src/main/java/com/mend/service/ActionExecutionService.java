package com.mend.service;

import com.mend.client.PaymentProviderClient;
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
import com.mend.dto.payment.PaymentExecutionRequest;
import com.mend.dto.payment.PaymentExecutionResult;
import com.mend.exception.ComplianceBlockedException;
import com.mend.exception.InvalidCampaignStateException;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ActionExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutionService.class);

    private final ActionIntentRepository actionIntentRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignAttemptRepository campaignAttemptRepository;
    private final ComplianceDecisionRepository complianceDecisionRepository;
    private final CampaignLifecycleService campaignLifecycleService;
    private final PaymentProviderClient paymentProviderClient;
    private final AuditService auditService;

    public ActionExecutionService(
            ActionIntentRepository actionIntentRepository,
            CampaignRepository campaignRepository,
            CampaignAttemptRepository campaignAttemptRepository,
            ComplianceDecisionRepository complianceDecisionRepository,
            CampaignLifecycleService campaignLifecycleService,
            PaymentProviderClient paymentProviderClient,
            AuditService auditService) {
        this.actionIntentRepository = actionIntentRepository;
        this.campaignRepository = campaignRepository;
        this.campaignAttemptRepository = campaignAttemptRepository;
        this.complianceDecisionRepository = complianceDecisionRepository;
        this.campaignLifecycleService = campaignLifecycleService;
        this.paymentProviderClient = paymentProviderClient;
        this.auditService = auditService;
    }

    public PaymentExecutionResult executeActionIntent(UUID intentId, String workerId) {
        Objects.requireNonNull(intentId, "intentId must not be null");

        // Step 1: Pre-execution validation & transaction state preparation
        ExecutionPreparation preparation = prepareExecution(intentId, workerId);
        if (!preparation.isExecutable()) {
            log.info("ActionIntent '{}' preparation halted: {}", intentId, preparation.getReason());
            return preparation.getHaltedResult();
        }

        ActionIntent intent = preparation.getIntent();
        Campaign campaign = preparation.getCampaign();

        // Step 2: Build untrusted-free PaymentExecutionRequest
        ActionType actionType = null;
        try {
            if (intent.getActionType() != null) {
                actionType = ActionType.valueOf(intent.getActionType());
            }
        } catch (Exception e) {
            actionType = ActionType.RETRY_PAYMENT;
        }
        if (actionType == null) {
            actionType = ActionType.RETRY_PAYMENT;
        }

        PaymentExecutionRequest request = new PaymentExecutionRequest(
                intent.getMerchantId(),
                intent.getCampaignId(),
                intent.getId(),
                campaign.getPaymentId(),
                campaign.getSubscriptionId(),
                actionType,
                intent.getAttemptNumber(),
                intent.getIdempotencyKey()
        );

        // Step 3: Invoke PaymentProviderClient OUTSIDE database transaction
        PaymentExecutionResult result;
        try {
            log.info("Invoking PaymentProviderClient for ActionIntent '{}' (key='{}')", intent.getId(), intent.getIdempotencyKey());
            result = paymentProviderClient.executeAction(request);
        } catch (Exception e) {
            log.error("Unhandled exception during PaymentProviderClient execution for ActionIntent '{}': {}", intent.getId(), e.getMessage(), e);
            result = PaymentExecutionResult.error("Unhandled provider exception: " + e.getMessage(), intent.getIdempotencyKey());
        }

        // Step 4: Transactional persistence of result & state synchronization
        return finalizeExecution(intent.getId(), workerId, result);
    }

    @Transactional
    public ExecutionPreparation prepareExecution(UUID intentId, String workerId) {
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

        return ExecutionPreparation.proceed(intent, campaign);
    }

    @Transactional
    public PaymentExecutionResult finalizeExecution(UUID intentId, String workerId, PaymentExecutionResult result) {
        ActionIntent intent = actionIntentRepository.findById(intentId)
                .orElseThrow(() -> new ResourceNotFoundException("ActionIntent not found with ID: " + intentId));

        Campaign campaign = campaignRepository.findById(intent.getCampaignId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with ID: " + intent.getCampaignId()));

        // Check if intent was already finalized by a concurrent worker
        if (intent.getStatus().isTerminal()) {
            log.warn("ActionIntent '{}' already reached terminal state '{}'. Skipping duplicate finalization.", intentId, intent.getStatus());
            return result;
        }

        Instant now = Instant.now();
        Instant completedAt = result.getExecutedAt() != null ? result.getExecutedAt() : now;

        // Step 1: Create & Persist CampaignAttempt (Enforcing UK uk_campaign_attempt_number)
        CampaignAttempt attempt = new CampaignAttempt(
                UUID.randomUUID(),
                campaign.getId(),
                intent.getAttemptNumber()
        );
        attempt.setActionType(intent.getActionType());
        attempt.setScheduledAt(intent.getScheduledAt());
        attempt.setStartedAt(intent.getClaimedAt() != null ? intent.getClaimedAt() : now);
        attempt.setCompletedAt(completedAt);
        attempt.setExternalReference(result.getExternalReference());

        if (result.isSuccess()) {
            attempt.setStatus("SUCCESS");
            attempt.setFailureReason(null);
        } else if (result.isFailure()) {
            attempt.setStatus("FAILED");
            attempt.setFailureReason(result.getMessage() != null ? result.getMessage() : "Payment declined");
        } else {
            attempt.setStatus("ERROR");
            attempt.setFailureReason(result.getMessage() != null ? result.getMessage() : "Provider error");
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
        if (result.isSuccess()) {
            intent.setStatus(ActionIntentStatus.SUCCEEDED);
            intent.setResponseReference(result.getExternalReference());
            intent.setCompletedAt(completedAt);
            actionIntentRepository.save(intent);

            campaignLifecycleService.transitionState(
                    campaign.getMerchantId(),
                    campaign.getId(),
                    CampaignStatus.RECOVERED,
                    "Payment recovery succeeded via provider (Ref: " + result.getExternalReference() + ")",
                    "SYSTEM",
                    null
            );

            auditService.logEvent(
                    intent.getMerchantId(), intent.getCampaignId(),
                    "ACTION_SUCCEEDED", "SYSTEM", null,
                    "ActionIntent " + intent.getId() + " SUCCEEDED with ref: " + result.getExternalReference()
            );

            log.info("ActionIntent '{}' SUCCEEDED. Campaign '{}' transitioned to RECOVERED.", intentId, campaign.getId());

        } else if (result.isFailure()) {
            intent.setStatus(ActionIntentStatus.FAILED);
            intent.setCompletedAt(completedAt);
            actionIntentRepository.save(intent);

            // Campaign state: EXECUTING -> FAILED -> EXHAUSTED
            campaignLifecycleService.transitionState(
                    campaign.getMerchantId(),
                    campaign.getId(),
                    CampaignStatus.FAILED,
                    "Action execution failed: " + result.getMessage(),
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

            auditService.logEvent(
                    intent.getMerchantId(), intent.getCampaignId(),
                    "ACTION_FAILED", "SYSTEM", null,
                    "ActionIntent " + intent.getId() + " FAILED: " + result.getMessage()
            );

            log.info("ActionIntent '{}' FAILED. Campaign '{}' transitioned to FAILED/EXHAUSTED.", intentId, campaign.getId());

        } else { // ERROR
            intent.setStatus(ActionIntentStatus.FAILED);
            intent.setCompletedAt(completedAt);
            actionIntentRepository.save(intent);

            // Transition EXECUTING -> FAILED (Without RECOVERED)
            campaignLifecycleService.transitionState(
                    campaign.getMerchantId(),
                    campaign.getId(),
                    CampaignStatus.FAILED,
                    "Action execution provider error/timeout: " + result.getMessage(),
                    "SYSTEM",
                    null
            );

            auditService.logEvent(
                    intent.getMerchantId(), intent.getCampaignId(),
                    "ACTION_ERROR", "SYSTEM", null,
                    "ActionIntent " + intent.getId() + " ERROR: " + result.getMessage()
            );

            log.warn("ActionIntent '{}' encountered provider ERROR. Campaign '{}' transitioned to FAILED.", intentId, campaign.getId());
        }

        return result;
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
