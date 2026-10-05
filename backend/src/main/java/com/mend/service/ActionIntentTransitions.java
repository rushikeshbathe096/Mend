package com.mend.service;

import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.provider.ProviderCapabilities;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The only component that changes an ActionIntent's status after creation (M2 invariant 11). Every method is one
 * conditional UPDATE fenced by the expected status, and by {@code claim_token} for worker-owned transitions. Each returns
 * {@code true} only if exactly one row changed; {@code false} means another actor got there first (stale / lost race).
 * Methods join the caller's transaction.
 */
@Service
public class ActionIntentTransitions {

    private static final Set<ActionIntentStatus> FINALIZE_TARGETS = EnumSet.of(
            ActionIntentStatus.SUCCEEDED, ActionIntentStatus.FAILED,
            ActionIntentStatus.AWAITING_PAYMENT, ActionIntentStatus.NEEDS_RECONCILIATION);
    private static final Set<ActionIntentStatus> ABANDON_TARGETS = EnumSet.of(ActionIntentStatus.FAILED, ActionIntentStatus.EXPIRED);

    private final ActionIntentRepository repository;
    private final ProviderCapabilities capabilities;

    public ActionIntentTransitions(ActionIntentRepository repository, ProviderCapabilities capabilities) {
        this.repository = repository;
        this.capabilities = capabilities;
    }

    /** READY → CLAIMED with a fresh token, only while the campaign is ACTION_PENDING and the intent is due. */
    @Transactional
    public boolean claim(UUID id, String workerId, String token, Instant now) {
        return repository.claimIntentAtomic(id, ActionIntentStatus.READY, ActionIntentStatus.CLAIMED, now, token, workerId,
                CampaignStatus.ACTION_PENDING) == 1;
    }

    /** SCHEDULED → READY. */
    @Transactional
    public boolean promote(UUID id) {
        return repository.promoteScheduled(id) == 1;
    }

    /** SCHEDULED/READY/PENDING past {@code expires_at} → EXPIRED. Never touches claimed or dispatched work. */
    @Transactional
    public boolean expire(UUID id, Instant now) {
        return repository.expireUnclaimed(id, now) == 1;
    }

    /** CLAIMED with an expired lease and NO dispatch → READY. Dispatched (EXECUTING) work is never released. */
    @Transactional
    public boolean releaseIfUndispatched(UUID id, Instant threshold) {
        return repository.releaseIfUndispatched(id, threshold) == 1;
    }

    /** CLAIMED → EXECUTING by the token holder; records {@code dispatched_at} and counts the attempt. Required before any provider call. */
    @Transactional
    public boolean dispatch(UUID id, String token, Instant now) {
        return token != null && repository.dispatch(id, token, now) == 1;
    }

    /** CLAIMED → FAILED/EXPIRED by the token holder, before any dispatch (e.g. compliance blocked, execution window passed). */
    @Transactional
    public boolean abandonClaim(UUID id, String token, ActionIntentStatus newStatus, Instant now) {
        if (!ABANDON_TARGETS.contains(newStatus)) {
            throw new IllegalArgumentException("abandonClaim target not allowed: " + newStatus);
        }
        return token != null && repository.abandonClaim(id, token, newStatus, now) == 1;
    }

    /** EXECUTING → SUCCEEDED/FAILED/AWAITING_PAYMENT/NEEDS_RECONCILIATION by the token holder. */
    @Transactional
    public boolean finalizeFromExecuting(UUID id, String token, ActionIntentStatus newStatus, String responseReference,
                                         Instant completedAt, Instant reconcileAfter) {
        if (!FINALIZE_TARGETS.contains(newStatus)) {
            throw new IllegalArgumentException("finalize target not allowed: " + newStatus);
        }
        return token != null
                && repository.finalizeFromExecuting(id, token, newStatus, responseReference, completedAt, reconcileAfter) == 1;
    }

    /** Any non-terminal status → CANCELLED (campaign cancellation). */
    @Transactional
    public boolean cancelNonTerminal(UUID id, Instant now) {
        return repository.cancelNonTerminal(id, now) == 1;
    }

    private static final Set<ActionIntentStatus> EVIDENCE_FROM = EnumSet.of(
            ActionIntentStatus.EXECUTING, ActionIntentStatus.AWAITING_PAYMENT, ActionIntentStatus.NEEDS_RECONCILIATION);
    private static final Set<ActionIntentStatus> EVIDENCE_TARGETS = EnumSet.of(ActionIntentStatus.SUCCEEDED, ActionIntentStatus.FAILED);

    /**
     * Provider evidence (webhook / reconciler) → SUCCEEDED or FAILED, only from one of {@code expected} (a subset of
     * EXECUTING, AWAITING_PAYMENT, NEEDS_RECONCILIATION). No claim token: evidence is not worker-owned.
     */
    @Transactional
    public boolean recordEvidence(UUID id, Set<ActionIntentStatus> expected, ActionIntentStatus newStatus, String responseReference,
                                  Instant completedAt) {
        if (expected.isEmpty() || !EVIDENCE_FROM.containsAll(expected)) {
            throw new IllegalArgumentException("recordEvidence expected statuses not allowed: " + expected);
        }
        if (!EVIDENCE_TARGETS.contains(newStatus)) {
            throw new IllegalArgumentException("recordEvidence target not allowed: " + newStatus);
        }
        return repository.recordEvidence(id, expected, newStatus, responseReference, completedAt) == 1;
    }

    /** PENDING/SCHEDULED/READY → CANCELLED (campaign cancellation of not-yet-claimed work). */
    @Transactional
    public boolean cancelIfExecutable(UUID id, Instant now) {
        return repository.cancelIfExecutable(id, now) == 1;
    }

    // ---- M2 U5 reconciler transitions (status-fenced; the reconciler is not the worker, so no claim token) ----

    private static final Set<ActionIntentStatus> RECONCILE_FROM = EnumSet.of(
            ActionIntentStatus.NEEDS_RECONCILIATION, ActionIntentStatus.AWAITING_PAYMENT);
    private static final Set<ActionIntentStatus> RECONCILE_TARGETS = EnumSet.of(ActionIntentStatus.AWAITING_PAYMENT,
            ActionIntentStatus.NEEDS_RECONCILIATION, ActionIntentStatus.SUCCEEDED, ActionIntentStatus.FAILED);

    /** EXECUTING whose dispatch is older than {@code threshold} → NEEDS_RECONCILIATION, due now. The dead worker's token stays,
     *  but its finalize requires EXECUTING and so fails. */
    @Transactional
    public boolean markStuck(UUID id, Instant threshold, Instant now) {
        return repository.markStuck(id, threshold, now) == 1;
    }

    /** NEEDS_RECONCILIATION / AWAITING_PAYMENT → another reconcile state or a terminal one, counting the reconcile attempt. */
    @Transactional
    public boolean reconcileTo(UUID id, ActionIntentStatus expected, ActionIntentStatus newStatus, String responseReference,
                               Instant now, Instant reconcileAfter) {
        if (!RECONCILE_FROM.contains(expected) || !RECONCILE_TARGETS.contains(newStatus) || expected == newStatus) {
            throw new IllegalArgumentException("reconcileTo not allowed: " + expected + " -> " + newStatus);
        }
        boolean terminal = newStatus == ActionIntentStatus.SUCCEEDED || newStatus == ActionIntentStatus.FAILED;
        if (!terminal && reconcileAfter == null) {
            throw new IllegalArgumentException("a non-terminal reconcile state needs reconcileAfter");
        }
        return repository.reconcileTo(id, expected, newStatus, responseReference, terminal ? now : null,
                terminal ? null : reconcileAfter) == 1;
    }

    /** Stay in {@code expected}, try again at {@code reconcileAfter} (backoff). */
    @Transactional
    public boolean deferReconcile(UUID id, ActionIntentStatus expected, Instant reconcileAfter) {
        if (!RECONCILE_FROM.contains(expected) || reconcileAfter == null) {
            throw new IllegalArgumentException("deferReconcile not allowed from " + expected);
        }
        return repository.deferReconcile(id, expected, reconcileAfter) == 1;
    }

    /**
     * SAFE_RETRY: NEEDS_RECONCILIATION → READY (same intent, same attempt key), only for action types whose provider resend is
     * idempotent and while dispatches remain. The capability gate is part of the UPDATE predicate, so no caller can bypass it.
     */
    @Transactional
    public boolean safeRetry(UUID id, int maxExecutionAttempts) {
        List<String> resendSafe = Arrays.stream(ActionType.values())
                .filter(capabilities::supportsIdempotentResend)
                .map(ActionType::name)
                .toList();
        if (resendSafe.isEmpty()) {
            return false;
        }
        return repository.safeRetry(id, resendSafe, maxExecutionAttempts) == 1;
    }
}
