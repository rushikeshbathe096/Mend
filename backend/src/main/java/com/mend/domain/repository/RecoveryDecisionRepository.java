package com.mend.domain.repository;

import com.mend.domain.entity.RecoveryDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecoveryDecisionRepository extends JpaRepository<RecoveryDecisionEntity, UUID> {

    List<RecoveryDecisionEntity> findByCampaignIdOrderByEvaluatedAtDesc(UUID campaignId);

    Optional<RecoveryDecisionEntity> findFirstByCampaignIdOrderByEvaluatedAtDesc(UUID campaignId);

    List<RecoveryDecisionEntity> findByMerchantId(UUID merchantId);
}
