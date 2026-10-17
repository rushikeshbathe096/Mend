package com.mend.domain.repository;

import com.mend.domain.entity.ReviewQueue;
import com.mend.domain.enums.ReviewQueueStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewQueueRepository extends JpaRepository<ReviewQueue, UUID> {
    List<ReviewQueue> findByMerchantIdAndStatus(UUID merchantId, ReviewQueueStatus status);
    Page<ReviewQueue> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<ReviewQueue> findByMerchantIdAndStatus(UUID merchantId, ReviewQueueStatus status, Pageable pageable);
    List<ReviewQueue> findByCampaignId(UUID campaignId);
    List<ReviewQueue> findByStatus(ReviewQueueStatus status);
    Optional<ReviewQueue> findFirstByCampaignIdAndStatusOrderByCreatedAtDesc(UUID campaignId, ReviewQueueStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReviewQueue r WHERE r.id = :id")
    Optional<ReviewQueue> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Bug sweep item 3: appends {@code entry} to a review that is still PENDING, in one statement (no lost update between two
     * reasons), unless {@code reason} is already in it. Returns 0 when the review is no longer PENDING or already has it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ReviewQueue r
               SET r.reason = CONCAT(COALESCE(r.reason, ''), :entry)
             WHERE r.id = :id
               AND r.status = com.mend.domain.enums.ReviewQueueStatus.PENDING
               AND (r.reason IS NULL OR LOCATE(:reason, r.reason) = 0)
            """)
    int appendReasonIfPending(@Param("id") UUID id, @Param("reason") String reason, @Param("entry") String entry);

    @Query("SELECT r.status, COUNT(r) FROM ReviewQueue r WHERE r.merchantId = :merchantId GROUP BY r.status")
    List<Object[]> countByMerchantGroupedByStatus(@Param("merchantId") UUID merchantId);
}
