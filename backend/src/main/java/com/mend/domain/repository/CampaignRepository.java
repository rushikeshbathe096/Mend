package com.mend.domain.repository;

import com.mend.domain.entity.Campaign;
import com.mend.domain.enums.CampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
    Optional<Campaign> findByMerchantIdAndPaymentId(UUID merchantId, String paymentId);
    Optional<Campaign> findByMerchantIdAndId(UUID merchantId, UUID id);
    
    long countByMerchantId(UUID merchantId);
    long countByMerchantIdAndCurrentState(UUID merchantId, CampaignStatus currentState);
    long countByMerchantIdAndCurrentStateIn(UUID merchantId, List<CampaignStatus> states);

    Page<Campaign> findByMerchantIdAndCustomerIdHash(UUID merchantId, String customerIdHash, Pageable pageable);
    List<Campaign> findByMerchantIdAndCustomerIdHash(UUID merchantId, String customerIdHash);

    @Query("SELECT DISTINCT c.customerIdHash FROM Campaign c WHERE c.merchantId = :merchantId AND c.customerIdHash IS NOT NULL")
    List<String> findDistinctCustomerIdHashesByMerchantId(@Param("merchantId") UUID merchantId);


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

    /** M2 U8: campaign-level correlation by the original payment's real provider id (not unique, see V15). */
    List<Campaign> findAllByMerchantIdAndProviderPaymentId(UUID merchantId, String providerPaymentId);

    /** M2 U8: campaign-level correlation by subscription (not unique). */
    List<Campaign> findAllByMerchantIdAndSubscriptionId(UUID merchantId, String subscriptionId);

    /**
     * M2 U8: set by whoever moved the campaign to RECOVERED (or CANCELLED on a late original capture). Bumps @Version so a
     * stale entity save fails loudly; clears the persistence context, so callers must reload entities they use afterwards.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.recoveryAttribution = :attribution, c.version = c.version + 1 WHERE c.id = :id")
    int setRecoveryAttribution(@Param("id") UUID id, @Param("attribution") com.mend.domain.enums.RecoveryAttribution attribution);

    /** Row lock for read-decide-transition sequences (M2 reconciler). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT c FROM Campaign c WHERE c.id = :id")
    Optional<Campaign> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    /** M3 U2.4: records a contact opt-out; a no-op (0) when the campaign is not the merchant's or already opted out. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE campaigns SET contact_opted_out_at = :now WHERE id = :id AND merchant_id = :merchantId AND contact_opted_out_at IS NULL",
            nativeQuery = true)
    int markContactOptedOut(@Param("id") UUID id, @Param("merchantId") UUID merchantId, @Param("now") Instant now);

    /** M3 U2.4: a merchant admin clears the opt-out; 0 when not the merchant's or not opted out. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE campaigns SET contact_opted_out_at = NULL WHERE id = :id AND merchant_id = :merchantId AND contact_opted_out_at IS NOT NULL",
            nativeQuery = true)
    int clearContactOptOut(@Param("id") UUID id, @Param("merchantId") UUID merchantId);

    /**
     * M3 U2.4: the opt-out as of now, read with a share lock inside the dispatch transaction. An opt-out committed
     * before this read blocks the dispatch; one arriving later waits for the dispatch transaction to commit.
     */
    @Query(value = "SELECT contact_opted_out_at IS NOT NULL FROM campaigns WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<Boolean> isContactOptedOutForShare(@Param("id") UUID id);
}
