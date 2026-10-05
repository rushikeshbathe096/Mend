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

    Optional<ActionIntent> findByResponseReference(String responseReference);

    Optional<ActionIntent> findFirstByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    List<ActionIntent> findByCampaignId(UUID campaignId);

    List<ActionIntent> findByMerchantId(UUID merchantId);

    Page<ActionIntent> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<ActionIntent> findByMerchantIdAndStatus(UUID merchantId, ActionIntentStatus status, Pageable pageable);

    Optional<ActionIntent> findByMerchantIdAndId(UUID merchantId, UUID id);

    long countByMerchantId(UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, ActionIntentStatus status);

    List<ActionIntent> findByStatus(ActionIntentStatus status);

    @Query("SELECT a FROM ActionIntent a WHERE a.status = :status AND a.scheduledAt <= :now ORDER BY a.scheduledAt ASC")
    List<ActionIntent> findDueIntents(
            @Param("status") ActionIntentStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );

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
}
