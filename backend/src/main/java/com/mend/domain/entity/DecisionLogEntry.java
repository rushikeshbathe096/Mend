package com.mend.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M4 U4.2 (ADR-004 §4): one append-only decision-log row. Never updated or deleted (V23 trigger). Written only through
 * {@code DecisionLogService}.
 */
@Entity
@Immutable
@Table(name = "decision_log")
public class DecisionLogEntry {

    public enum Point { ASSIGNMENT, STRATEGY, COMPLIANCE, DISPATCH }

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "UUID")
    private UUID campaignId;

    @Column(name = "intent_id", columnDefinition = "UUID")
    private UUID intentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_point", nullable = false, length = 20)
    private Point decisionPoint;

    @Column(nullable = false, length = 60)
    private String chosen;

    @Column(name = "reason_code", length = 60)
    private String reasonCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    private List<String> alternatives;

    @Column(name = "propensity_bp", nullable = false)
    private int propensityBp;

    @Column(name = "source_id", columnDefinition = "UUID")
    private UUID sourceId;

    @Column(name = "taxonomy_version", length = 50)
    private String taxonomyVersion;

    @Column(name = "strategy_version", length = 50)
    private String strategyVersion;

    @Column(name = "compliance_version", length = 50)
    private String complianceVersion;

    @Column(name = "assignment_version", length = 50)
    private String assignmentVersion;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected DecisionLogEntry() {
    }

    public DecisionLogEntry(UUID merchantId, UUID campaignId, UUID intentId, Point decisionPoint, String chosen, String reasonCode,
                            List<String> alternatives, int propensityBp, UUID sourceId, String taxonomyVersion,
                            String strategyVersion, String complianceVersion, String assignmentVersion, Instant decidedAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.campaignId = campaignId;
        this.intentId = intentId;
        this.decisionPoint = decisionPoint;
        this.chosen = chosen;
        this.reasonCode = reasonCode;
        this.alternatives = List.copyOf(alternatives);
        this.propensityBp = propensityBp;
        this.sourceId = sourceId;
        this.taxonomyVersion = taxonomyVersion;
        this.strategyVersion = strategyVersion;
        this.complianceVersion = complianceVersion;
        this.assignmentVersion = assignmentVersion;
        this.decidedAt = decidedAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getCampaignId() { return campaignId; }
    public UUID getIntentId() { return intentId; }
    public Point getDecisionPoint() { return decisionPoint; }
    public String getChosen() { return chosen; }
    public String getReasonCode() { return reasonCode; }
    public List<String> getAlternatives() { return alternatives; }
    public int getPropensityBp() { return propensityBp; }
    public UUID getSourceId() { return sourceId; }
    public String getTaxonomyVersion() { return taxonomyVersion; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getComplianceVersion() { return complianceVersion; }
    public String getAssignmentVersion() { return assignmentVersion; }
    public Instant getDecidedAt() { return decidedAt; }
}
