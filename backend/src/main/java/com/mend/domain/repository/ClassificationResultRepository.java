package com.mend.domain.repository;

import com.mend.domain.entity.ClassificationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClassificationResultRepository extends JpaRepository<ClassificationResult, UUID> {
    List<ClassificationResult> findByCampaignId(UUID campaignId);
    
    @Query("SELECT c FROM ClassificationResult c WHERE c.campaignId = :campaignId ORDER BY c.createdAt DESC LIMIT 1")
    Optional<ClassificationResult> findLatestByCampaignId(@Param("campaignId") UUID campaignId);
}
