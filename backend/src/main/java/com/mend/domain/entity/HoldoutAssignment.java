package com.mend.domain.entity;

import com.mend.experiment.Arm;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * M4 U4.1 (ADR-004 §1): a campaign's holdout assignment, written once at its first eligibility and never changed
 * (V22 trigger rejects UPDATE / DELETE / TRUNCATE). Inserted only through {@code HoldoutAssignmentRepository.insertIfAbsent}.
 */
@Entity
@Immutable
@Table(name = "holdout_assignments")
public class HoldoutAssignment {

    public static final String SOURCE_HASH = "HASH";
    public static final String SOURCE_INVOICE_REUSE = "INVOICE_REUSE";

    @Id
    @Column(name = "campaign_id", columnDefinition = "UUID")
    private UUID campaignId;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Arm arm;

    @Column(nullable = false)
    private int bucket;

    @Column(name = "holdout_rate_bp", nullable = false)
    private int holdoutRateBp;

    @Column(name = "salt_version", nullable = false)
    private int saltVersion;

    @Column(name = "hash_version", nullable = false, length = 50)
    private String hashVersion;

    /** {@code HASH} or {@code INVOICE_REUSE}. */
    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    protected HoldoutAssignment() {
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public Arm getArm() {
        return arm;
    }

    public int getBucket() {
        return bucket;
    }

    public int getHoldoutRateBp() {
        return holdoutRateBp;
    }

    public int getSaltVersion() {
        return saltVersion;
    }

    public String getHashVersion() {
        return hashVersion;
    }

    public String getSource() {
        return source;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
