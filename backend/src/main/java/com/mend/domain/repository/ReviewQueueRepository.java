package com.mend.domain.repository;

import com.mend.domain.entity.ReviewQueue;
import com.mend.domain.enums.ReviewQueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewQueueRepository extends JpaRepository<ReviewQueue, UUID> {
    List<ReviewQueue> findByMerchantIdAndStatus(UUID merchantId, ReviewQueueStatus status);
    List<ReviewQueue> findByCampaignId(UUID campaignId);
    List<ReviewQueue> findByStatus(ReviewQueueStatus status);
}
