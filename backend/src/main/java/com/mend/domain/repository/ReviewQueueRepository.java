package com.mend.domain.repository;

import com.mend.domain.entity.ReviewQueue;
import com.mend.domain.enums.ReviewQueueStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    @Query("SELECT r.status, COUNT(r) FROM ReviewQueue r WHERE r.merchantId = :merchantId GROUP BY r.status")
    List<Object[]> countByMerchantGroupedByStatus(@Param("merchantId") UUID merchantId);
}
