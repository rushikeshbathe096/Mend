package com.mend.domain.repository;

import com.mend.domain.entity.CampaignAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface CampaignAttemptRepository extends JpaRepository<CampaignAttempt, UUID> {
    List<CampaignAttempt> findByCampaignId(UUID campaignId);
    Optional<CampaignAttempt> findByCampaignIdAndAttemptNumber(UUID campaignId, Integer attemptNumber);
    Optional<CampaignAttempt> findFirstByCampaignIdOrderByAttemptNumberDesc(UUID campaignId);

    @Query("SELECT COUNT(a) FROM CampaignAttempt a JOIN Campaign c ON a.campaignId = c.id WHERE c.merchantId = :merchantId")
    long countByMerchantId(@Param("merchantId") UUID merchantId);
}
