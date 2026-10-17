package com.mend.domain.repository;

import com.mend.domain.entity.ComplianceDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ComplianceDecisionRepository extends JpaRepository<ComplianceDecisionEntity, UUID> {

    List<ComplianceDecisionEntity> findByCampaignIdOrderByEvaluatedAtDesc(UUID campaignId);

    Optional<ComplianceDecisionEntity> findFirstByCampaignIdOrderByEvaluatedAtDesc(UUID campaignId);


    List<ComplianceDecisionEntity> findByMerchantId(UUID merchantId);
}
