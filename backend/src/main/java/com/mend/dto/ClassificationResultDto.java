package com.mend.dto;

import com.mend.domain.entity.ClassificationResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class ClassificationResultDto {
    private UUID id;
    private UUID campaignId;
    private UUID eventId;
    private String failureClass;
    private BigDecimal confidence;
    private String strategyRecommendation;
    private String reasoning;
    private Map<String, Object> evidence;
    private String modelVersion;
    private Instant createdAt;

    public ClassificationResultDto() {
    }

    public static ClassificationResultDto fromEntity(ClassificationResult result) {
        if (result == null) return null;
        ClassificationResultDto dto = new ClassificationResultDto();
        dto.setId(result.getId());
        dto.setCampaignId(result.getCampaignId());
        dto.setEventId(result.getEventId());
        dto.setFailureClass(result.getFailureClass());
        dto.setConfidence(result.getConfidence());
        dto.setStrategyRecommendation(result.getStrategyRecommendation());
        dto.setReasoning(result.getReasoning());
        dto.setEvidence(result.getEvidence());
        dto.setModelVersion(result.getModelVersion());
        dto.setCreatedAt(result.getCreatedAt());
        return dto;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(UUID campaignId) {
        this.campaignId = campaignId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getFailureClass() {
        return failureClass;
    }

    public void setFailureClass(String failureClass) {
        this.failureClass = failureClass;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getStrategyRecommendation() {
        return strategyRecommendation;
    }

    public void setStrategyRecommendation(String strategyRecommendation) {
        this.strategyRecommendation = strategyRecommendation;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }

    public void setEvidence(Map<String, Object> evidence) {
        this.evidence = evidence;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
