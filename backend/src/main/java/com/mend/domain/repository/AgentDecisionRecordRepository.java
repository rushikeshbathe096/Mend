package com.mend.domain.repository;

import com.mend.domain.entity.AgentDecisionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentDecisionRecordRepository extends JpaRepository<AgentDecisionRecord, UUID> {

    List<AgentDecisionRecord> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    Optional<AgentDecisionRecord> findFirstByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    List<AgentDecisionRecord> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);
}
