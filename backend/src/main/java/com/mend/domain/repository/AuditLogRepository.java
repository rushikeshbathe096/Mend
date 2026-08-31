package com.mend.domain.repository;

import com.mend.domain.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByMerchantId(UUID merchantId);
    List<AuditLog> findByCampaignId(UUID campaignId);
    List<AuditLog> findByActorId(UUID actorId);
}
