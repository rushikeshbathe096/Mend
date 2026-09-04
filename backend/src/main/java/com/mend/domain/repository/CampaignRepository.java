package com.mend.domain.repository;

import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.CampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    List<Campaign> findByMerchantId(UUID merchantId);
    Page<Campaign> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<Campaign> findByMerchantIdAndCurrentState(UUID merchantId, CampaignStatus status, Pageable pageable);
    Optional<Campaign> findByPaymentId(String paymentId);
    Optional<Campaign> findByMerchantIdAndPaymentId(UUID merchantId, String paymentId);
    Optional<Campaign> findByMerchantIdAndSubscriptionId(UUID merchantId, String subscriptionId);
    Optional<Campaign> findByMerchantIdAndId(UUID merchantId, UUID id);
    
    long countByMerchantId(UUID merchantId);
    long countByMerchantIdAndCurrentState(UUID merchantId, CampaignStatus currentState);
    long countByMerchantIdAndCurrentStateIn(UUID merchantId, List<CampaignStatus> states);

    @Query("SELECT c FROM Campaign c WHERE c.merchantId = :merchantId AND c.nextActionAt <= :now AND c.currentState IN :states")
    List<Campaign> findScheduledCampaignsByMerchant(@Param("merchantId") UUID merchantId, @Param("now") Instant now, @Param("states") List<CampaignStatus> states);
}
