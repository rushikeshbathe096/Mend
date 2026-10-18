package com.mend.domain.repository;

import com.mend.domain.entity.DecisionLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DecisionLogRepository extends JpaRepository<DecisionLogEntry, UUID> {

    List<DecisionLogEntry> findByCampaignIdOrderByDecidedAtAscIdAsc(UUID campaignId);

    List<DecisionLogEntry> findByCampaignIdAndDecisionPoint(UUID campaignId, DecisionLogEntry.Point decisionPoint);
}
