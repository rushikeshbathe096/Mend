package com.mend.domain.repository;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
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

    @Query("SELECT a FROM ActionIntent a WHERE a.status = 'CLAIMED' AND a.claimedAt < :expiredThreshold")
    List<ActionIntent> findExpiredClaims(@Param("expiredThreshold") Instant expiredThreshold);

    @Query("SELECT a FROM ActionIntent a WHERE a.status IN ('SCHEDULED', 'READY', 'PENDING') AND a.expiresAt IS NOT NULL AND a.expiresAt < :now")
    List<ActionIntent> findExpiredIntents(@Param("now") Instant now);

    @Modifying
    @Query("UPDATE ActionIntent a SET a.status = :newStatus, a.claimedAt = :claimedAt, a.claimToken = :claimToken, a.workerId = :workerId WHERE a.id = :id AND a.status = :expectedStatus")
    int claimIntentAtomic(
            @Param("id") UUID id,
            @Param("expectedStatus") ActionIntentStatus expectedStatus,
            @Param("newStatus") ActionIntentStatus newStatus,
            @Param("claimedAt") Instant claimedAt,
            @Param("claimToken") String claimToken,
            @Param("workerId") String workerId
    );
}
