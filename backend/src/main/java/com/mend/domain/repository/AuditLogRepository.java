package com.mend.domain.repository;

import com.mend.domain.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByMerchantId(UUID merchantId);
    List<AuditLog> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);
    Page<AuditLog> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<AuditLog> findByMerchantIdAndEventType(UUID merchantId, String eventType, Pageable pageable);
    Page<AuditLog> findByMerchantIdAndActorType(UUID merchantId, String actorType, Pageable pageable);
    Page<AuditLog> findByMerchantIdAndCampaignId(UUID merchantId, UUID campaignId, Pageable pageable);
    List<AuditLog> findByCampaignId(UUID campaignId);
    List<AuditLog> findByCampaignIdOrderByCreatedAtAsc(UUID campaignId);
    List<AuditLog> findByActorId(UUID actorId);
}
