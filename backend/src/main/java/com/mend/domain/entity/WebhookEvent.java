package com.mend.domain.entity;

import com.mend.domain.enums.WebhookEventStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_events", indexes = {
    @Index(name = "idx_webhook_events_external_event_id", columnList = "external_event_id", unique = true),
    @Index(name = "idx_webhook_events_processing_status", columnList = "processing_status"),
    @Index(name = "idx_webhook_events_received_at", columnList = "received_at")
})
public class WebhookEvent {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String externalEventId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(length = 50)
    private String source;

    @Column(nullable = false)
    private Instant receivedAt;

    @Column
    private Instant eventCreatedAt;

    @Column(length = 255)
    private String payloadHash;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private WebhookEventStatus processingStatus = WebhookEventStatus.RECEIVED;

    @Column
    private Instant processedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String rawPayload;

    @Column(columnDefinition = "UUID")
    private UUID merchantId;

    public WebhookEvent() {
    }

    public WebhookEvent(UUID id, String externalEventId, String eventType) {
        this.id = id;
        this.externalEventId = externalEventId;
        this.eventType = eventType;
        this.receivedAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public void setExternalEventId(String externalEventId) {
        this.externalEventId = externalEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getEventCreatedAt() {
        return eventCreatedAt;
    }

    public void setEventCreatedAt(Instant eventCreatedAt) {
        this.eventCreatedAt = eventCreatedAt;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public WebhookEventStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(WebhookEventStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }
}
