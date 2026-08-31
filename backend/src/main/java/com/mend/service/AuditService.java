package com.mend.service;

import com.mend.domain.entity.AuditLog;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public AuditLog logStateTransition(
            UUID merchantId,
            UUID campaignId,
            CampaignStatus previousState,
            CampaignStatus newState,
            String actorType,
            UUID actorId,
            String reason) {
        AuditLog auditLog = new AuditLog(UUID.randomUUID(), "CAMPAIGN_STATE_TRANSITION");
        auditLog.setMerchantId(merchantId);
        auditLog.setCampaignId(campaignId);
        auditLog.setActorType(actorType != null ? actorType : "SYSTEM");
        auditLog.setActorId(actorId);
        auditLog.setReason(reason);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("previousState", previousState != null ? previousState.name() : null);
        metadata.put("newState", newState != null ? newState.name() : null);
        metadata.put("timestamp", Instant.now().toString());
        auditLog.setMetadata(metadata);

        return auditLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getCampaignAuditLogs(UUID campaignId) {
        return auditLogRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId);
    }
}
