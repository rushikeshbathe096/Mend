package com.mend.domain.repository;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActionIntentRepository extends JpaRepository<ActionIntent, UUID> {

    Optional<ActionIntent> findByIdempotencyKey(String idempotencyKey);

    /** M2 U8: all intents with this provider reference; more than one is an integrity problem the caller must surface. */
    List<ActionIntent> findAllByResponseReference(String responseReference);

    /** M2 U8: the campaign's intents, row-locked, so no claim/dispatch can change them between read and cancel. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ActionIntent a WHERE a.campaignId = :campaignId ORDER BY a.createdAt ASC")
    List<ActionIntent> findByCampaignIdForUpdate(@Param("campaignId") UUID campaignId);

    Optional<ActionIntent> findFirstByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    List<ActionIntent> findByCampaignId(UUID campaignId);

    List<ActionIntent> findByMerchantId(UUID merchantId);

    Page<ActionIntent> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<ActionIntent> findByMerchantIdAndStatus(UUID merchantId, ActionIntentStatus status, Pageable pageable);

    Optional<ActionIntent> findByMerchantIdAndId(UUID merchantId, UUID id);

    long countByMerchantId(UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, ActionIntentStatus status);

    List<ActionIntent> findByStatus(ActionIntentStatus status);


    @Query("""
            SELECT a
              FROM ActionIntent a
             WHERE a.status = :status
               AND a.scheduledAt <= :now
               AND EXISTS (
                   SELECT c.id
                     FROM Campaign c
                    WHERE c.id = a.campaignId
                      AND c.merchantId = a.merchantId
                      AND c.currentState = :campaignStatus
               )
               AND NOT EXISTS (
                   SELECT s.id
                     FROM ActionIntent s
                    WHERE s.merchantId = a.merchantId
                      AND s.invoiceKey = a.invoiceKey
                      AND s.id <> a.id
                      AND s.status IN (com.mend.domain.enums.ActionIntentStatus.CLAIMED, com.mend.domain.enums.ActionIntentStatus.EXECUTING,
                                       com.mend.domain.enums.ActionIntentStatus.NEEDS_RECONCILIATION,
                                       com.mend.domain.enums.ActionIntentStatus.AWAITING_PAYMENT)
               )
             ORDER BY a.scheduledAt ASC
            """)
    List<ActionIntent> findDueClaimableIntents(
            @Param("status") ActionIntentStatus status,
            @Param("campaignStatus") CampaignStatus campaignStatus,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("SELECT a FROM ActionIntent a WHERE a.status = 'CLAIMED' AND a.claimedAt < :expiredThreshold")
    List<ActionIntent> findExpiredClaims(@Param("expiredThreshold") Instant expiredThreshold);

    @Query("SELECT a FROM ActionIntent a WHERE a.status IN ('SCHEDULED', 'READY', 'PENDING') AND a.expiresAt IS NOT NULL AND a.expiresAt < :now")
    List<ActionIntent> findExpiredIntents(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1, a.status = :newStatus,
                   a.claimedAt = :claimedAt,
                   a.claimToken = :claimToken,
                   a.workerId = :workerId
             WHERE a.id = :id
               AND a.status = :expectedStatus
               AND a.scheduledAt <= :claimedAt
               AND EXISTS (
                   SELECT c.id
                     FROM Campaign c
                    WHERE c.id = a.campaignId
                      AND c.merchantId = a.merchantId
                      AND c.currentState = :expectedCampaignStatus
               )
               AND NOT EXISTS (
                   SELECT s.id
                     FROM ActionIntent s
                    WHERE s.merchantId = a.merchantId
                      AND s.invoiceKey = a.invoiceKey
                      AND s.id <> a.id
                      AND s.status IN (com.mend.domain.enums.ActionIntentStatus.CLAIMED, com.mend.domain.enums.ActionIntentStatus.EXECUTING,
                                       com.mend.domain.enums.ActionIntentStatus.NEEDS_RECONCILIATION,
                                       com.mend.domain.enums.ActionIntentStatus.AWAITING_PAYMENT)
               )
            """)
    int claimIntentAtomic(
            @Param("id") UUID id,
            @Param("expectedStatus") ActionIntentStatus expectedStatus,
            @Param("newStatus") ActionIntentStatus newStatus,
            @Param("claimedAt") Instant claimedAt,
            @Param("claimToken") String claimToken,
            @Param("workerId") String workerId,
            @Param("expectedCampaignStatus") CampaignStatus expectedCampaignStatus
    );

    // ---- M2 fenced transitions (only ActionIntentTransitions calls these) ----

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ActionIntent a SET a.version = a.version + 1, a.status = com.mend.domain.enums.ActionIntentStatus.READY WHERE a.id = :id AND a.status = com.mend.domain.enums.ActionIntentStatus.SCHEDULED")
    int promoteScheduled(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1, a.status = com.mend.domain.enums.ActionIntentStatus.EXPIRED, a.completedAt = :now
             WHERE a.id = :id
               AND a.status IN (com.mend.domain.enums.ActionIntentStatus.SCHEDULED, com.mend.domain.enums.ActionIntentStatus.READY,
                                com.mend.domain.enums.ActionIntentStatus.PENDING)
               AND a.expiresAt IS NOT NULL AND a.expiresAt < :now
            """)
    int expireUnclaimed(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1, a.status = com.mend.domain.enums.ActionIntentStatus.READY,
                   a.claimedAt = NULL, a.claimToken = NULL, a.workerId = NULL
             WHERE a.id = :id
               AND a.status = com.mend.domain.enums.ActionIntentStatus.CLAIMED
               AND a.dispatchedAt IS NULL
               AND a.claimedAt < :threshold
            """)
    int releaseIfUndispatched(@Param("id") UUID id, @Param("threshold") Instant threshold);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1, a.status = com.mend.domain.enums.ActionIntentStatus.EXECUTING,
                   a.dispatchedAt = :now,
                   a.executionAttempts = a.executionAttempts + 1
             WHERE a.id = :id
               AND a.status = com.mend.domain.enums.ActionIntentStatus.CLAIMED
               AND a.claimToken = :token
            """)
    int dispatch(@Param("id") UUID id, @Param("token") String token, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1, a.status = :newStatus, a.completedAt = :now
             WHERE a.id = :id
               AND a.status = com.mend.domain.enums.ActionIntentStatus.CLAIMED
               AND a.claimToken = :token
            """)
    int abandonClaim(@Param("id") UUID id, @Param("token") String token, @Param("newStatus") ActionIntentStatus newStatus,
                     @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1, a.status = :newStatus,
                   a.responseReference = COALESCE(:responseReference, a.responseReference),
                   a.completedAt = :completedAt,
                   a.reconcileAfter = :reconcileAfter
             WHERE a.id = :id
               AND a.status = com.mend.domain.enums.ActionIntentStatus.EXECUTING
               AND a.claimToken = :token
            """)
    int finalizeFromExecuting(@Param("id") UUID id, @Param("token") String token, @Param("newStatus") ActionIntentStatus newStatus,
                              @Param("responseReference") String responseReference, @Param("completedAt") Instant completedAt,
                              @Param("reconcileAfter") Instant reconcileAfter);

    /**
     * M2 U9: MEND-side deferral. Only the claim-token holder, only from EXECUTING, and only for outcomes where nothing reached
     * the provider (the caller's classification): back to READY at {@code scheduledAt}, ownership cleared.
     * Bug sweep item 4: the dispatch moves from executionAttempts (the SAFE_RETRY budget) to deferAttempts.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1, a.status = com.mend.domain.enums.ActionIntentStatus.READY,
                   a.scheduledAt = :scheduledAt,
                   a.executionAttempts = a.executionAttempts - 1, a.deferAttempts = a.deferAttempts + 1,
                   a.claimToken = NULL, a.workerId = NULL, a.claimedAt = NULL, a.dispatchedAt = NULL
             WHERE a.id = :id
               AND a.status = com.mend.domain.enums.ActionIntentStatus.EXECUTING
               AND a.claimToken = :token
            """)
    int deferFromExecuting(@Param("id") UUID id, @Param("token") String token, @Param("scheduledAt") Instant scheduledAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1, a.status = com.mend.domain.enums.ActionIntentStatus.CANCELLED, a.completedAt = :now
             WHERE a.id = :id
               AND a.status NOT IN (com.mend.domain.enums.ActionIntentStatus.SUCCEEDED, com.mend.domain.enums.ActionIntentStatus.FAILED,
                                    com.mend.domain.enums.ActionIntentStatus.CANCELLED, com.mend.domain.enums.ActionIntentStatus.EXPIRED)
            """)
    int cancelNonTerminal(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1,
                   a.status = :newStatus,
                   a.responseReference = COALESCE(:responseReference, a.responseReference),
                   a.completedAt = :completedAt
             WHERE a.id = :id
               AND a.status IN :expected
            """)
    int recordEvidence(@Param("id") UUID id, @Param("expected") java.util.Collection<ActionIntentStatus> expected,
                       @Param("newStatus") ActionIntentStatus newStatus, @Param("responseReference") String responseReference,
                       @Param("completedAt") Instant completedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1,
                   a.status = com.mend.domain.enums.ActionIntentStatus.CANCELLED, a.completedAt = :now
             WHERE a.id = :id
               AND a.status IN (com.mend.domain.enums.ActionIntentStatus.PENDING, com.mend.domain.enums.ActionIntentStatus.SCHEDULED,
                                com.mend.domain.enums.ActionIntentStatus.READY)
            """)
    int cancelIfExecutable(@Param("id") UUID id, @Param("now") Instant now);

    // ---- M2 U5 reconciler (only ActionIntentTransitions / ExecutionReconciler call these) ----

    /** EXECUTING rows whose dispatch is older than the threshold (worker presumed dead). Legacy NULL dispatch → claimed/created. */
    @Query("""
            SELECT a.id FROM ActionIntent a
             WHERE a.status = com.mend.domain.enums.ActionIntentStatus.EXECUTING
               AND COALESCE(a.dispatchedAt, a.claimedAt, a.createdAt) < :threshold
            """)
    List<UUID> findStuckExecutingIds(@Param("threshold") Instant threshold);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1,
                   a.status = com.mend.domain.enums.ActionIntentStatus.NEEDS_RECONCILIATION,
                   a.reconcileAfter = :now
             WHERE a.id = :id
               AND a.status = com.mend.domain.enums.ActionIntentStatus.EXECUTING
               AND COALESCE(a.dispatchedAt, a.claimedAt, a.createdAt) < :threshold
            """)
    int markStuck(@Param("id") UUID id, @Param("threshold") Instant threshold, @Param("now") Instant now);

    /** Due reconciliation rows, locked; rows locked by another reconciler are skipped (SKIP LOCKED). Caller must be in a tx. */
    @Query(value = """
            SELECT id FROM action_intents
             WHERE status IN ('NEEDS_RECONCILIATION', 'AWAITING_PAYMENT')
               AND (reconcile_after IS NULL OR reconcile_after <= :now)
             ORDER BY reconcile_after NULLS FIRST
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockDueForReconciliation(@Param("now") Instant now, @Param("limit") int limit);

    /** Reconcile lease: pushes the picked rows' next due time so no other reconciler takes them meanwhile. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1, a.reconcileAfter = :leaseUntil
             WHERE a.id IN :ids
               AND a.status IN (com.mend.domain.enums.ActionIntentStatus.NEEDS_RECONCILIATION,
                                com.mend.domain.enums.ActionIntentStatus.AWAITING_PAYMENT)
            """)
    int leaseForReconciliation(@Param("ids") java.util.Collection<UUID> ids, @Param("leaseUntil") Instant leaseUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1,
                   a.status = :newStatus,
                   a.responseReference = COALESCE(:responseReference, a.responseReference),
                   a.completedAt = :completedAt,
                   a.reconcileAfter = :reconcileAfter,
                   a.reconcileAttempts = a.reconcileAttempts + 1
             WHERE a.id = :id
               AND a.status = :expected
               AND a.version = :seenVersion
            """)
    int reconcileTo(@Param("id") UUID id, @Param("expected") ActionIntentStatus expected, @Param("newStatus") ActionIntentStatus newStatus,
                    @Param("responseReference") String responseReference, @Param("completedAt") Instant completedAt,
                    @Param("reconcileAfter") Instant reconcileAfter, @Param("seenVersion") long seenVersion);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1,
                   a.reconcileAfter = :reconcileAfter,
                   a.reconcileAttempts = a.reconcileAttempts + 1
             WHERE a.id = :id
               AND a.status = :expected
               AND a.version = :seenVersion
            """)
    int deferReconcile(@Param("id") UUID id, @Param("expected") ActionIntentStatus expected, @Param("reconcileAfter") Instant reconcileAfter,
                       @Param("seenVersion") long seenVersion);

    /** SAFE_RETRY: same intent (same attempt key) back to READY; only for resend-safe action types; ownership cleared. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.version = a.version + 1,
                   a.status = com.mend.domain.enums.ActionIntentStatus.READY,
                   a.claimToken = NULL, a.workerId = NULL, a.claimedAt = NULL, a.dispatchedAt = NULL,
                   a.reconcileAfter = NULL,
                   a.reconcileAttempts = a.reconcileAttempts + 1
             WHERE a.id = :id
               AND a.status = com.mend.domain.enums.ActionIntentStatus.NEEDS_RECONCILIATION
               AND a.actionType IN :resendSafeTypes
               AND a.executionAttempts < :maxExecutionAttempts
               AND a.version = :seenVersion
            """)
    int safeRetry(@Param("id") UUID id, @Param("resendSafeTypes") java.util.Collection<String> resendSafeTypes,
                  @Param("maxExecutionAttempts") int maxExecutionAttempts, @Param("seenVersion") long seenVersion);

    /**
     * M3 U2.4 (ALREADY_PAID reply): brings an AWAITING_PAYMENT intent's next evidence lookup forward to {@code now}. Only
     * the schedule changes (no status, no version bump: an in-flight reconcile keeps its version fence, and the
     * AWAITING_PAYMENT branch is a read-only lookup that never resends). NEEDS_RECONCILIATION is never touched.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ActionIntent a
               SET a.reconcileAfter = :now
             WHERE a.id = :id
               AND a.status = com.mend.domain.enums.ActionIntentStatus.AWAITING_PAYMENT
               AND (a.reconcileAfter IS NULL OR a.reconcileAfter > :now)
            """)
    int bringForwardPaymentLookup(@Param("id") UUID id, @Param("now") Instant now);
}
