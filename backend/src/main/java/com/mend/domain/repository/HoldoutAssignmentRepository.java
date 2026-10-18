package com.mend.domain.repository;

import com.mend.domain.entity.HoldoutAssignment;
import com.mend.experiment.Arm;
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
public interface HoldoutAssignmentRepository extends JpaRepository<HoldoutAssignment, UUID> {

    /**
     * M4 U4.1: writes the assignment unless the campaign already has one (never re-assigned). 1 = written, 0 = kept.
     * No exception on a concurrent insert, so the caller's transaction stays usable.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO holdout_assignments (campaign_id, merchant_id, arm, bucket, holdout_rate_bp, salt_version, hash_version, source, assigned_at)
            VALUES (:campaignId, :merchantId, :arm, :bucket, :rateBp, :saltVersion, :hashVersion, :source, :assignedAt)
            ON CONFLICT (campaign_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("campaignId") UUID campaignId, @Param("merchantId") UUID merchantId, @Param("arm") String arm,
                       @Param("bucket") int bucket, @Param("rateBp") int rateBp, @Param("saltVersion") int saltVersion,
                       @Param("hashVersion") String hashVersion, @Param("source") String source,
                       @Param("assignedAt") Instant assignedAt);

    /** The first assignment of another campaign of the same invoice (one arm per invoice, ADR-004 §1). */
    @Query(value = """
            SELECT a.* FROM holdout_assignments a JOIN campaigns c ON c.id = a.campaign_id
            WHERE a.merchant_id = :merchantId AND c.invoice_key = :invoiceKey AND a.campaign_id <> :campaignId
            ORDER BY a.assigned_at, a.campaign_id LIMIT 1
            """, nativeQuery = true)
    Optional<HoldoutAssignment> findFirstForInvoice(@Param("merchantId") UUID merchantId, @Param("invoiceKey") String invoiceKey,
                                                    @Param("campaignId") UUID campaignId);

    @Query("SELECT a.arm FROM HoldoutAssignment a WHERE a.campaignId = :campaignId")
    Optional<Arm> findArmByCampaignId(@Param("campaignId") UUID campaignId);

    List<HoldoutAssignment> findByMerchantIdOrderByAssignedAtAsc(UUID merchantId);
}
