package com.mend.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** M4 U4.3 (ADR-004 §5): an evaluation snapshot's metadata; immutable (V24 trigger). */
@Entity
@Immutable
@Table(name = "eval_snapshots")
public class EvalSnapshot implements Persistable<UUID> {

    public enum Status { VALID, INVALID }

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "schema_version", nullable = false, length = 30)
    private String schemaVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "window_from", nullable = false)
    private Instant windowFrom;

    @Column(name = "window_to", nullable = false)
    private Instant windowTo;

    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    @Column(name = "horizon_days", nullable = false)
    private int horizonDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> filters;

    @Column(name = "provider_mode", nullable = false, length = 30)
    private String providerMode;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "decision_count", nullable = false)
    private int decisionCount;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    private List<Map<String, String>> errors;

    protected EvalSnapshot() {
    }

    public EvalSnapshot(UUID id, String schemaVersion, Instant createdAt, Instant windowFrom, Instant windowTo, Instant asOf,
                        int horizonDays, Map<String, Object> filters, String providerMode, int rowCount, int decisionCount,
                        String sha256, Status status, List<Map<String, String>> errors) {
        this.id = id;
        this.schemaVersion = schemaVersion;
        this.createdAt = createdAt;
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.asOf = asOf;
        this.horizonDays = horizonDays;
        this.filters = filters;
        this.providerMode = providerMode;
        this.rowCount = rowCount;
        this.decisionCount = decisionCount;
        this.sha256 = sha256;
        this.status = status;
        this.errors = errors;
    }

    @Override
    public UUID getId() { return id; }

    @Override
    @Transient
    public boolean isNew() { return true; }

    public String getSchemaVersion() { return schemaVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getWindowFrom() { return windowFrom; }
    public Instant getWindowTo() { return windowTo; }
    public Instant getAsOf() { return asOf; }
    public int getHorizonDays() { return horizonDays; }
    public Map<String, Object> getFilters() { return filters; }
    public String getProviderMode() { return providerMode; }
    public int getRowCount() { return rowCount; }
    public int getDecisionCount() { return decisionCount; }
    public String getSha256() { return sha256; }
    public Status getStatus() { return status; }
    public List<Map<String, String>> getErrors() { return errors; }
}
