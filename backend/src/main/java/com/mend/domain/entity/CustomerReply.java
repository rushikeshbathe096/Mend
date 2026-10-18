package com.mend.domain.entity;

import com.mend.domain.enums.ReplyStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A customer's reply to a recovery message (PII, ≤ 2000 characters; retention 90 days, purge job M5-owned). Never logged. */
@Entity
@Table(name = "customer_replies")
public class CustomerReply {

    public static final int MAX_TEXT_LENGTH = 2000;

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "merchant_id", nullable = false, columnDefinition = "UUID")
    private UUID merchantId;

    @Column(name = "campaign_id", nullable = false, columnDefinition = "UUID")
    private UUID campaignId;

    @Column(nullable = false, length = MAX_TEXT_LENGTH)
    private String text;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ReplyStatus status = ReplyStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 100)
    private String lastError;

    @Column(name = "created_by", columnDefinition = "UUID")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerReply() {
    }

    public CustomerReply(UUID id, UUID merchantId, UUID campaignId, String text, UUID createdBy) {
        Instant now = Instant.now();
        this.id = id;
        this.merchantId = merchantId;
        this.campaignId = campaignId;
        this.text = text;
        this.createdBy = createdBy;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getCampaignId() { return campaignId; }
    public String getText() { return text; }
    public ReplyStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
