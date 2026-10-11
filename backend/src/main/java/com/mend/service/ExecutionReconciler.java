package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.domain.repository.CampaignAttemptRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.provider.LookupResult;
import com.mend.provider.MerchantRef;
import com.mend.provider.PaymentEvidence;
import com.mend.provider.PaymentLinkSnapshot;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.ProviderCapabilities;
import com.mend.statemachine.CampaignStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * M2 U5: resolves possibly-dispatched work from provider evidence (M2-execution-safety.md §4 and the §7 decision table).
 * Never calls a create/POST operation: it only reads ({@code findPaymentLinkByReference}, {@code getPaymentLink},
 * {@code getPayment}); a resend happens only through SAFE_RETRY (back to READY, the normal executor path with the same
 * attempt key), and only for action types whose resend the provider makes idempotent. Every picked row either moves or
 * gets a later {@code reconcile_after}.
 */
@Component
public class ExecutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReconciler.class);

    static final String RECONCILIATION_ESCALATED = "RECONCILIATION_ESCALATED";
    static final String AMBIGUOUS_DEBIT = "AMBIGUOUS_DEBIT";

    /** What one cycle did; used by tests and logs. */
    public record Cycle(int markedStuck, List<UUID> picked) {
        static final Cycle IDLE = new Cycle(0, List.of());
    }

    private final ActionIntentRepository intents;
    private final ActionIntentTransitions transitions;
    private final PaymentProvider provider;
    private final ProviderCapabilities capabilities;
    private final CampaignRepository campaigns;
    private final CampaignAttemptRepository attempts;
    private final CampaignLifecycleService campaignLifecycleService;
    private final CampaignStateMachine campaignStateMachine;
    private final HumanApprovalService humanApprovalService;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final ReconcileBackoff backoff;
    private final Clock clock;
    private final Settings settings;

    /** Tunables (ADR-002 OD-3). */
    public record Settings(boolean enabled, boolean scheduledEnabled, int batchSize, Duration lease, Duration stuckAfter,
                           Duration notFoundGrace, int maxExecutionAttempts, int deferralCap) {
        public Settings {
            if (batchSize < 1 || !lease.isPositive() || !stuckAfter.isPositive() || notFoundGrace.isNegative()
                    || maxExecutionAttempts < 1 || deferralCap < 0) {
                throw new IllegalStateException("invalid mend.reconciler settings: batch=" + batchSize + " lease=" + lease + " stuck="
                        + stuckAfter + " grace=" + notFoundGrace + " maxExecutionAttempts=" + maxExecutionAttempts + " deferralCap=" + deferralCap);
            }
        }
    }

    @Autowired
    public ExecutionReconciler(ActionIntentRepository intents, ActionIntentTransitions transitions, PaymentProvider provider,
                               ProviderCapabilities capabilities, CampaignRepository campaigns, CampaignAttemptRepository attempts,
                               CampaignLifecycleService campaignLifecycleService, CampaignStateMachine campaignStateMachine,
                               HumanApprovalService humanApprovalService, AuditService auditService,
                               PlatformTransactionManager transactionManager,
                               @Value("${mend.reconciler.enabled:true}") boolean enabled,
                               @Value("${mend.reconciler.scheduled-enabled:true}") boolean scheduledEnabled,
                               @Value("${mend.reconciler.batch-size:50}") int batchSize,
                               @Value("${mend.reconciler.lease-seconds:60}") long leaseSeconds,
                               @Value("${mend.reconciler.stuck-seconds:120}") long stuckSeconds,
                               @Value("${mend.reconciler.not-found-grace-seconds:120}") long notFoundGraceSeconds,
                               @Value("${mend.reconciler.max-execution-attempts:3}") int maxExecutionAttempts,
                               @Value("${mend.reconciler.deferral-cap:5}") int deferralCap,
                               @Value("${mend.reconciler.backoff-base-seconds:30}") long backoffBaseSeconds,
                               @Value("${mend.reconciler.backoff-cap-seconds:21600}") long backoffCapSeconds) {
        this(intents, transitions, provider, capabilities, campaigns, attempts, campaignLifecycleService, campaignStateMachine,
                humanApprovalService, auditService, transactionManager,
                new Settings(enabled, scheduledEnabled, batchSize, Duration.ofSeconds(leaseSeconds), Duration.ofSeconds(stuckSeconds),
                        Duration.ofSeconds(notFoundGraceSeconds), maxExecutionAttempts, deferralCap),
                new ReconcileBackoff(Duration.ofSeconds(backoffBaseSeconds), Duration.ofSeconds(backoffCapSeconds), RandomGenerator.getDefault()),
                Clock.systemUTC());
    }

    ExecutionReconciler(ActionIntentRepository intents, ActionIntentTransitions transitions, PaymentProvider provider,
                        ProviderCapabilities capabilities, CampaignRepository campaigns, CampaignAttemptRepository attempts,
                        CampaignLifecycleService campaignLifecycleService, CampaignStateMachine campaignStateMachine,
                        HumanApprovalService humanApprovalService, AuditService auditService,
                        PlatformTransactionManager transactionManager, Settings settings, ReconcileBackoff backoff, Clock clock) {
        this.intents = intents;
        this.transitions = transitions;
        this.provider = provider;
        this.capabilities = capabilities;
        this.campaigns = campaigns;
        this.attempts = attempts;
        this.campaignLifecycleService = campaignLifecycleService;
        this.campaignStateMachine = campaignStateMachine;
        this.humanApprovalService = humanApprovalService;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.settings = settings;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mend.reconciler.poll-interval-ms:15000}")
    public void scheduledCycle() {
        if (settings.scheduledEnabled()) {
            runCycle();
        }
    }

    public Cycle runCycle() {
        if (!settings.enabled()) {
            return Cycle.IDLE;
        }
        int stuck = markStuck();
        List<UUID> picked = leaseDueRows();
        for (UUID id : picked) {
            try {
                reconcile(id);
            } catch (RuntimeException e) {
                log.error("event=reconcile_error intent_id={} exception={}", id, e.getClass().getSimpleName());
                log.debug("reconcile_error detail intent_id={}", id, e);
                deferAfterError(id, e);
            }
        }
        return new Cycle(stuck, picked);
    }

    // ---- a. stuck sweep ----

    private int markStuck() {
        Instant now = clock.instant();
        Instant threshold = now.minus(settings.stuckAfter());
        int marked = 0;
        for (UUID id : intents.findStuckExecutingIds(threshold)) {
            Boolean applied = tx.execute(status -> {
                if (!transitions.markStuck(id, threshold, now)) {
                    return false;
                }
                intents.findById(id).ifPresent(intent -> auditService.logEvent(intent.getMerchantId(), intent.getCampaignId(),
                        "EXECUTION_STUCK", "SYSTEM", null,
                        "intent=" + id + " EXECUTING->NEEDS_RECONCILIATION stuck_after_s=" + settings.stuckAfter().toSeconds()));
                return true;
            });
            if (Boolean.TRUE.equals(applied)) {
                marked++;
                log.warn("event=execution_stuck intent_id={}", id);
            }
        }
        return marked;
    }

    // ---- b. pick + reconcile lease (short transaction, SKIP LOCKED) ----

    private List<UUID> leaseDueRows() {
        Instant now = clock.instant();
        List<UUID> ids = tx.execute(status -> {
            List<UUID> due = intents.lockDueForReconciliation(now, settings.batchSize());
            if (!due.isEmpty()) {
                intents.leaseForReconciliation(due, now.plus(settings.lease()));
            }
            return due;
        });
        return ids == null ? List.of() : ids;
    }

    // ---- c. per row, outside any transaction while the provider is asked ----

    private void reconcile(UUID id) {
        Optional<ActionIntent> loaded = intents.findById(id);
        if (loaded.isEmpty()) {
            return;
        }
        ActionIntent intent = loaded.get();
        switch (intent.getStatus()) {
            case NEEDS_RECONCILIATION -> reconcileUnknown(intent);
            case AWAITING_PAYMENT -> reconcileAwaitingPayment(intent);
            default -> log.debug("event=reconcile_skip intent_id={} status={}", id, intent.getStatus());
        }
    }

    private void reconcileUnknown(ActionIntent intent) {
        if (!resendSafe(intent)) {
            // Debit (or unknown action): no debit lookup in M2 and NEVER a resend. Defer until the budget is spent, then a human.
            if (intent.getReconcileAttempts() >= settings.deferralCap()) {
                escalate(intent, AMBIGUOUS_DEBIT);
            } else {
                defer(intent, ActionIntentStatus.NEEDS_RECONCILIATION);
            }
            return;
        }
        MerchantRef merchant = new MerchantRef(intent.getMerchantId());
        LookupResult<PaymentLinkSnapshot> lookup = provider.findPaymentLinkByReference(merchant, ProviderAttemptKey.forIntent(intent.getId()));
        switch (lookup) {
            case LookupResult.Found<PaymentLinkSnapshot> found -> applyLink(intent, ActionIntentStatus.NEEDS_RECONCILIATION, found.value());
            case LookupResult.NotFound<PaymentLinkSnapshot> notFound -> linkNotFound(intent);
            case LookupResult.Unavailable<PaymentLinkSnapshot> unavailable -> {
                log.info("event=reconcile_lookup_unavailable intent_id={} cause={}", intent.getId(), unavailable.cause());
                defer(intent, ActionIntentStatus.NEEDS_RECONCILIATION);
            }
        }
    }

    private void reconcileAwaitingPayment(ActionIntent intent) {
        MerchantRef merchant = new MerchantRef(intent.getMerchantId());
        String linkId = intent.getResponseReference();
        LookupResult<PaymentLinkSnapshot> lookup = PaymentLinkSnapshot.isLinkId(linkId)
                ? provider.getPaymentLink(merchant, linkId)
                : provider.findPaymentLinkByReference(merchant, ProviderAttemptKey.forIntent(intent.getId()));
        switch (lookup) {
            case LookupResult.Found<PaymentLinkSnapshot> found -> applyLink(intent, ActionIntentStatus.AWAITING_PAYMENT, found.value());
            case LookupResult.NotFound<PaymentLinkSnapshot> notFound -> {
                // Should not happen for a link we issued: back to reconciliation by reference, loudly.
                log.warn("event=awaiting_payment_link_not_found intent_id={}", intent.getId());
                inTx(() -> {
                    if (transitions.reconcileTo(intent.getId(), ActionIntentStatus.AWAITING_PAYMENT, ActionIntentStatus.NEEDS_RECONCILIATION,
                            null, clock.instant(), nextDue(intent), intent.getVersion())) {
                        audit(intent, "RECONCILE_LINK_NOT_FOUND", "AWAITING_PAYMENT->NEEDS_RECONCILIATION");
                    }
                });
            }
            case LookupResult.Unavailable<PaymentLinkSnapshot> unavailable -> {
                log.info("event=reconcile_lookup_unavailable intent_id={} cause={}", intent.getId(), unavailable.cause());
                defer(intent, ActionIntentStatus.AWAITING_PAYMENT);
            }
        }
    }

    private void applyLink(ActionIntent intent, ActionIntentStatus from, PaymentLinkSnapshot link) {
        switch (link.state()) {
            case CREATED, PARTIALLY_PAID -> {
                if (from == ActionIntentStatus.NEEDS_RECONCILIATION) {
                    inTx(() -> {
                        if (transitions.reconcileTo(intent.getId(), from, ActionIntentStatus.AWAITING_PAYMENT, link.providerLinkId(),
                                clock.instant(), nextDue(intent), intent.getVersion())) {
                            updateAttempt(intent, "ISSUED", link.providerLinkId(), null);
                            audit(intent, "RECONCILED_AWAITING_PAYMENT", "NEEDS_RECONCILIATION->AWAITING_PAYMENT link=" + link.providerLinkId());
                        }
                    });
                } else {
                    defer(intent, from);
                }
            }
            case PAID -> {
                Optional<String> captured = capturedPayment(intent, link);
                if (captured.isPresent()) {
                    succeed(intent, from, captured.get());
                } else {
                    // A link state is not money (invariant 2): without CAPTURED payment evidence, wait.
                    log.warn("event=paid_link_without_captured_evidence intent_id={} link={}", intent.getId(), link.providerLinkId());
                    defer(intent, from);
                }
            }
            case EXPIRED, CANCELLED -> fail(intent, from, "LINK_" + link.state());
            case UNRECOGNIZED -> defer(intent, from);
        }
    }

    private Optional<String> capturedPayment(ActionIntent intent, PaymentLinkSnapshot link) {
        MerchantRef merchant = new MerchantRef(intent.getMerchantId());
        for (String paymentId : link.capturedPaymentIds()) {
            if (provider.getPayment(merchant, paymentId) instanceof LookupResult.Found<PaymentEvidence> found
                    && found.value().status().isRecovery()) {
                return Optional.of(paymentId);
            }
        }
        return Optional.empty();
    }

    private void linkNotFound(ActionIntent intent) {
        Instant now = clock.instant();
        Instant dispatched = firstNonNull(intent.getDispatchedAt(), intent.getClaimedAt(), intent.getCreatedAt());
        if (dispatched != null && now.isBefore(dispatched.plus(settings.notFoundGrace()))) {
            defer(intent, ActionIntentStatus.NEEDS_RECONCILIATION);
            return;
        }
        if (intent.getExecutionAttempts() >= settings.maxExecutionAttempts()) {
            escalate(intent, RECONCILIATION_ESCALATED);   // a terminal campaign is handled inside, same policy as SAFE_RETRY's
            return;
        }
        safeRetry(intent);
    }

    // ---- d. SAFE_RETRY / escalation ----

    private void safeRetry(ActionIntent intent) {
        inTx(() -> {
            Campaign campaign = campaigns.findByIdForUpdate(intent.getCampaignId()).orElseThrow();
            CampaignStatus state = campaign.getCurrentState();
            if (campaignStateMachine.isTerminal(state)) {
                // Nothing exists at the provider and the campaign is over: do not resend.
                failLinkOnTerminalCampaign(intent, state);
                return;
            }
            if (state != CampaignStatus.EXECUTING && state != CampaignStatus.ACTION_PENDING) {
                escalateInTx(intent, RECONCILIATION_ESCALATED);
                return;
            }
            if (!transitions.safeRetry(intent.getId(), settings.maxExecutionAttempts(), intent.getVersion())) {
                return;
            }
            if (state == CampaignStatus.EXECUTING) {
                campaignLifecycleService.transitionState(intent.getMerchantId(), campaign.getId(), CampaignStatus.ACTION_PENDING,
                        "RETRY_DEFERRED: SAFE_RETRY of intent " + intent.getId(), "SYSTEM", null);
            }
            audit(intent, "SAFE_RETRY", "NEEDS_RECONCILIATION->READY attempt_key=" + ProviderAttemptKey.forIntent(intent.getId()).value()
                    + " execution_attempts=" + intent.getExecutionAttempts());
            log.info("event=safe_retry intent_id={} execution_attempts={}", intent.getId(), intent.getExecutionAttempts());
        });
    }

    private void escalate(ActionIntent intent, String reasonCode) {
        inTx(() -> escalateInTx(intent, reasonCode));
    }

    /** Intent → FAILED, campaign back to ELIGIBLE (approval requires it), review item with the reason code. */
    private void escalateInTx(ActionIntent intent, String reasonCode) {
        CampaignStatus state = campaigns.findByIdForUpdate(intent.getCampaignId()).orElseThrow().getCurrentState();
        if (campaignStateMachine.isTerminal(state) && RECONCILIATION_ESCALATED.equals(reasonCode)) {
            // A link the provider does not know, on a campaign that is over: nothing for a human to decide.
            failLinkOnTerminalCampaign(intent, state);
            return;
        }
        // AMBIGUOUS_DEBIT always gets a review, whatever the campaign state: money may have moved.
        if (!transitions.reconcileTo(intent.getId(), ActionIntentStatus.NEEDS_RECONCILIATION, ActionIntentStatus.FAILED, null,
                clock.instant(), null, intent.getVersion())) {
            return;
        }
        if (state == CampaignStatus.EXECUTING) {
            transition(intent, CampaignStatus.FAILED, reasonCode + ": outcome of intent " + intent.getId() + " unresolved");
            state = CampaignStatus.FAILED;
        }
        if (state == CampaignStatus.FAILED || state == CampaignStatus.ACTION_PENDING) {
            transition(intent, CampaignStatus.ELIGIBLE, reasonCode + ": routed to human review");
        }
        updateAttempt(intent, "UNRESOLVED", null, reasonCode);
        humanApprovalService.createReview(intent.getMerchantId(), intent.getCampaignId(),
                reasonCode + ": intent " + intent.getId() + " outcome not resolved by reconciliation; human decision required");
        audit(intent, reasonCode, "NEEDS_RECONCILIATION->FAILED review_created execution_attempts=" + intent.getExecutionAttempts()
                + " reconcile_attempts=" + intent.getReconcileAttempts());
        log.warn("event=reconcile_escalated intent_id={} reason={}", intent.getId(), reasonCode);
    }

    private void failLinkOnTerminalCampaign(ActionIntent intent, CampaignStatus campaignState) {
        if (transitions.reconcileTo(intent.getId(), ActionIntentStatus.NEEDS_RECONCILIATION, ActionIntentStatus.FAILED, null,
                clock.instant(), null, intent.getVersion())) {
            updateAttempt(intent, "FAILED", null, "NOT_FOUND_CAMPAIGN_" + campaignState);
            audit(intent, "RECONCILER_FAILED", "NEEDS_RECONCILIATION->FAILED NOT_FOUND_CAMPAIGN_" + campaignState);
        }
    }

    // ---- e. terminal outcomes ----

    private void succeed(ActionIntent intent, ActionIntentStatus from, String paymentId) {
        inTx(() -> {
            if (!transitions.reconcileTo(intent.getId(), from, ActionIntentStatus.SUCCEEDED, paymentId, clock.instant(), null, intent.getVersion())) {
                return;
            }
            updateAttempt(intent, "SUCCESS", paymentId, null);
            CampaignStatus state = campaigns.findByIdForUpdate(intent.getCampaignId()).orElseThrow().getCurrentState();
            if (campaignStateMachine.isValidTransition(state, CampaignStatus.RECOVERED)) {
                transition(intent, CampaignStatus.RECOVERED, "Captured payment " + paymentId + " confirmed by reconciler");
                campaigns.setRecoveryAttribution(intent.getCampaignId(), com.mend.domain.enums.RecoveryAttribution.MEND);
            } else {
                // Real money on a campaign that cannot be RECOVERED (e.g. cancelled while the link was payable): surface it.
                log.warn("event=captured_on_terminal_campaign intent_id={} campaign_state={}", intent.getId(), state);
                audit(intent, "CAPTURED_ON_TERMINAL_CAMPAIGN", "payment=" + paymentId + " campaign_state=" + state);
                humanApprovalService.createReview(intent.getMerchantId(), intent.getCampaignId(), "CAPTURED_ON_TERMINAL_CAMPAIGN: intent "
                        + intent.getId() + " payment " + paymentId + " captured while campaign is " + state + "; human decision required");
            }
            audit(intent, "RECONCILER_SUCCEEDED", from + "->SUCCEEDED payment=" + paymentId);
        });
    }

    private void fail(ActionIntent intent, ActionIntentStatus from, String code) {
        inTx(() -> {
            if (!transitions.reconcileTo(intent.getId(), from, ActionIntentStatus.FAILED, null, clock.instant(), null, intent.getVersion())) {
                return;
            }
            updateAttempt(intent, "FAILED", null, code);
            // Existing failure path: EXECUTING -> FAILED -> EXHAUSTED.
            CampaignStatus state = campaigns.findByIdForUpdate(intent.getCampaignId()).orElseThrow().getCurrentState();
            if (state == CampaignStatus.ACTION_PENDING) {
                transition(intent, CampaignStatus.EXECUTING, "Reconciler resolving intent " + intent.getId());
                state = CampaignStatus.EXECUTING;
            }
            if (state == CampaignStatus.EXECUTING) {
                transition(intent, CampaignStatus.FAILED, "Action execution failed: " + code);
                state = CampaignStatus.FAILED;
            }
            if (state == CampaignStatus.FAILED) {
                transition(intent, CampaignStatus.EXHAUSTED, "Campaign attempts exhausted following failed action execution");
            }
            audit(intent, "RECONCILER_FAILED", from + "->FAILED " + code);
        });
    }

    // ---- helpers ----

    /** An error while reconciling a row: back off from a fresh read (no hot loop) and leave an audit trace. */
    private void deferAfterError(UUID id, RuntimeException error) {
        try {
            inTx(() -> intents.findById(id)
                    .filter(intent -> intent.getStatus() == ActionIntentStatus.NEEDS_RECONCILIATION
                            || intent.getStatus() == ActionIntentStatus.AWAITING_PAYMENT)
                    .ifPresent(intent -> {
                        if (transitions.deferReconcile(id, intent.getStatus(), nextDue(intent), intent.getVersion())) {
                            audit(intent, "RECONCILE_ERROR", "status=" + intent.getStatus() + " error=" + error.getClass().getSimpleName());
                        }
                    }));
        } catch (RuntimeException deferFailed) {
            // The row keeps its lease as reconcile_after: retried after the lease, never in a tight loop.
            log.error("event=reconcile_error_defer_failed intent_id={} exception={}", id, deferFailed.getClass().getSimpleName());
        }
    }

    private void defer(ActionIntent intent, ActionIntentStatus from) {
        transitions.deferReconcile(intent.getId(), from, nextDue(intent), intent.getVersion());
    }

    private Instant nextDue(ActionIntent intent) {
        return clock.instant().plus(backoff.delay(intent.getReconcileAttempts()));
    }

    private boolean resendSafe(ActionIntent intent) {
        try {
            return intent.getActionType() != null && capabilities.supportsIdempotentResend(ActionType.valueOf(intent.getActionType()));
        } catch (IllegalArgumentException unknownType) {
            return false;
        }
    }

    private void transition(ActionIntent intent, CampaignStatus target, String reason) {
        campaignLifecycleService.transitionState(intent.getMerchantId(), intent.getCampaignId(), target, reason, "SYSTEM", null);
    }

    private void updateAttempt(ActionIntent intent, String status, String externalReference, String failureCode) {
        attempts.findByCampaignIdAndAttemptNumber(intent.getCampaignId(), intent.getAttemptNumber()).ifPresent(attempt -> {
            attempt.setStatus(status);
            if (externalReference != null) {
                attempt.setExternalReference(externalReference);
            }
            attempt.setFailureReason(failureCode);
            if (!"ISSUED".equals(status)) {
                attempt.setCompletedAt(clock.instant());
            }
            attempts.save(attempt);
        });
    }

    private void audit(ActionIntent intent, String eventType, String detail) {
        auditService.logEvent(intent.getMerchantId(), intent.getCampaignId(), eventType, "SYSTEM", null,
                "intent=" + intent.getId() + " " + detail);
    }

    private void inTx(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    private static Instant firstNonNull(Instant... values) {
        for (Instant value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
