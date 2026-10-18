package com.mend.domain.repository;

import com.mend.domain.entity.CustomerReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Replies and their parsing state. Every state change is a conditional update fenced by status and by the attempt number
 * the worker claimed, so a stale worker (whose lease ran out and whose row was re-claimed) cannot overwrite a newer result.
 */
public interface CustomerReplyRepository extends JpaRepository<CustomerReply, UUID> {

    List<CustomerReply> findByCampaignIdAndMerchantIdOrderByCreatedAtDesc(UUID campaignId, UUID merchantId);

    Optional<CustomerReply> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Due PENDING replies, locked; rows locked by another worker are skipped. Caller must be in a transaction. */
    @Query(value = """
            SELECT id FROM customer_replies
             WHERE status = 'PENDING' AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    /** Claims a locked due reply: counts the attempt and leases the row until {@code leaseUntil}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerReply r
               SET r.attempts = r.attempts + 1, r.nextAttemptAt = :leaseUntil, r.updatedAt = :now
             WHERE r.id = :id AND r.status = com.mend.domain.enums.ReplyStatus.PENDING
            """)
    int claim(@Param("id") UUID id, @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerReply r
               SET r.status = com.mend.domain.enums.ReplyStatus.PARSED, r.lastError = NULL, r.updatedAt = :now
             WHERE r.id = :id AND r.status = com.mend.domain.enums.ReplyStatus.PENDING AND r.attempts = :attempts
            """)
    int markParsed(@Param("id") UUID id, @Param("attempts") int attempts, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerReply r
               SET r.nextAttemptAt = :nextAttemptAt, r.lastError = :error, r.updatedAt = :now
             WHERE r.id = :id AND r.status = com.mend.domain.enums.ReplyStatus.PENDING AND r.attempts = :attempts
            """)
    int scheduleRetry(@Param("id") UUID id, @Param("attempts") int attempts, @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("error") String error, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerReply r
               SET r.status = com.mend.domain.enums.ReplyStatus.FAILED, r.lastError = :error, r.updatedAt = :now
             WHERE r.id = :id AND r.status = com.mend.domain.enums.ReplyStatus.PENDING AND r.attempts = :attempts
            """)
    int markFailed(@Param("id") UUID id, @Param("attempts") int attempts, @Param("error") String error, @Param("now") Instant now);

    /** A human retry of a FAILED reply (it has no suggestion): back to PENDING with a fresh attempt budget. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerReply r
               SET r.status = com.mend.domain.enums.ReplyStatus.PENDING, r.attempts = 0, r.nextAttemptAt = :now,
                   r.lastError = NULL, r.updatedAt = :now
             WHERE r.id = :id AND r.merchantId = :merchantId AND r.status = com.mend.domain.enums.ReplyStatus.FAILED
            """)
    int retryFailed(@Param("id") UUID id, @Param("merchantId") UUID merchantId, @Param("now") Instant now);
}
