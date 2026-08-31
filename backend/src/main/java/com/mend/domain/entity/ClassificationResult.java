package com.mend.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "classification_results", indexes = {
    @Index(name = "idx_classification_results_campaign_id", columnList = "campaign_id"),
    @Index(name = "idx_classification_results_event_id", columnList = "event_id"),
    @Index(name = "idx_classification_results_created_at", columnList = "created_at"),
    @Index(name = "idx_classification_results_failure_class", columnList = "failure_class")
})
public class ClassificationResult {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "campaign_id", columnDefinition = "UUID")
    private UUID campaignId;

    @Column(name = "event_id", columnDefinition = "UUID", unique = true)
    private UUID eventId;

    @Column(nullable = false, length = 50)
    private String failureClass;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(columnDefinition = "TEXT")
    private String strategyRecommendation;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private Map<String, Object> evidence;

    @Column(length = 50)
    private String modelVersion;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public ClassificationResult() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    public ClassificationResult(UUID id, UUID campaignId, String failureClass, BigDecimal confidence) {
        this.id = id != null ? id : UUID.randomUUID();
        this.campaignId = campaignId;
        this.failureClass = failureClass;
        this.confidence = confidence;
        this.createdAt = Instant.now();
    }

    public ClassificationResult(UUID id, UUID eventId, UUID campaignId, String failureClass, BigDecimal confidence, String strategyRecommendation, String reasoning, String modelVersion) {
        this.id = id != null ? id : UUID.randomUUID();
        this.eventId = eventId;
        this.campaignId = campaignId;
        this.failureClass = failureClass;
        this.confidence = confidence;
        this.strategyRecommendation = strategyRecommendation;
        this.reasoning = reasoning;
        this.modelVersion = modelVersion;
        this.createdAt = Instant.now();
    }

    // Getters and setters
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
