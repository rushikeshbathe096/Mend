package com.mend.domain.repository;

import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {
    List<Campaign> findByMerchantIdAndCurrentState(UUID merchantId, CampaignStatus status);
    List<Campaign> findByMerchantId(UUID merchantId);
    Optional<Campaign> findByPaymentId(String paymentId);
    
    @Query("SELECT c FROM Campaign c WHERE c.merchantId = :merchantId AND c.nextActionAt <= :now AND c.currentState IN :states")
    List<Campaign> findScheduledCampaignsByMerchant(@Param("merchantId") UUID merchantId, @Param("now") Instant now, @Param("states") List<CampaignStatus> states);
}
