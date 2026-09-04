package com.mend.dto;

import com.mend.domain.entity.AuditLog;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class AuditLogDto {
    private UUID id;
    private UUID merchantId;
    private UUID campaignId;
    private String eventType;
    private String actorType;
    private UUID actorId;
    private String reason;
    private Map<String, Object> evidence;
    private Map<String, Object> metadata;
    private Instant createdAt;

    public AuditLogDto() {
    }

    public static AuditLogDto fromEntity(AuditLog log) {
        if (log == null) return null;
        AuditLogDto dto = new AuditLogDto();
        dto.setId(log.getId());
        dto.setMerchantId(log.getMerchantId());
        dto.setCampaignId(log.getCampaignId());
        dto.setEventType(log.getEventType());
        dto.setActorType(log.getActorType());
        dto.setActorId(log.getActorId());
        dto.setReason(log.getReason());
        dto.setEvidence(log.getEvidence());
        dto.setMetadata(log.getMetadata());
        dto.setCreatedAt(log.getCreatedAt());
        return dto;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(UUID campaignId) {
        this.campaignId = campaignId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }

    public void setEvidence(Map<String, Object> evidence) {
        this.evidence = evidence;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
