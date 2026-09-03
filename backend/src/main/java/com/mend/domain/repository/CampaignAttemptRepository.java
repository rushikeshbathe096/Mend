package com.mend.domain.repository;

import com.mend.domain.entity.CampaignAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignAttemptRepository extends JpaRepository<CampaignAttempt, UUID> {
    List<CampaignAttempt> findByCampaignId(UUID campaignId);
    Optional<CampaignAttempt> findByCampaignIdAndAttemptNumber(UUID campaignId, Integer attemptNumber);
    Optional<CampaignAttempt> findFirstByCampaignIdOrderByAttemptNumberDesc(UUID campaignId);
}
