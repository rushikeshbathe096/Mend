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

    Page<Campaign> findByMerchantIdAndCustomerIdHash(UUID merchantId, String customerIdHash, Pageable pageable);
    List<Campaign> findByMerchantIdAndCustomerIdHash(UUID merchantId, String customerIdHash);

    @Query("SELECT DISTINCT c.customerIdHash FROM Campaign c WHERE c.merchantId = :merchantId AND c.customerIdHash IS NOT NULL")
    List<String> findDistinctCustomerIdHashesByMerchantId(@Param("merchantId") UUID merchantId);

    @Query("SELECT c FROM Campaign c WHERE c.merchantId = :merchantId AND c.nextActionAt <= :now AND c.currentState IN :states")
    List<Campaign> findScheduledCampaignsByMerchant(@Param("merchantId") UUID merchantId, @Param("now") Instant now, @Param("states") List<CampaignStatus> states);

    @Query("""
            SELECT c FROM Campaign c
            WHERE c.merchantId = :merchantId
              AND (:state IS NULL OR c.currentState = :state)
              AND (:failureClass IS NULL OR :failureClass = '' OR LOWER(c.failureClass) = LOWER(:failureClass))
              AND (:search IS NULL OR :search = ''
                   OR LOWER(c.paymentId) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(COALESCE(c.customerIdHash, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<Campaign> searchMerchantCampaigns(@Param("merchantId") UUID merchantId,
                                           @Param("state") CampaignStatus state,
                                           @Param("failureClass") String failureClass,
                                           @Param("search") String search,
                                           Pageable pageable);
}
