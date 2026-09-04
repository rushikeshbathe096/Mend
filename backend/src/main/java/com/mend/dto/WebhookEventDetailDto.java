package com.mend.dto;

import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.enums.WebhookPublishStatus;

import java.time.Instant;
import java.util.UUID;

public class WebhookEventDetailDto {
    private UUID id;
    private String externalEventId;
    private String eventType;
    private String source;
    private Instant receivedAt;
    private Instant eventCreatedAt;
    private String payloadHash;
    private WebhookEventStatus processingStatus;
    private Instant processedAt;
    private String errorMessage;
    private UUID merchantId;
    private WebhookPublishStatus publishStatus;
    private Instant publishedAt;

    public WebhookEventDetailDto() {
    }

    public static WebhookEventDetailDto fromEntity(WebhookEvent event) {
        if (event == null) return null;
        WebhookEventDetailDto dto = new WebhookEventDetailDto();
        dto.setId(event.getId());
        dto.setExternalEventId(event.getExternalEventId());
        dto.setEventType(event.getEventType());
        dto.setSource(event.getSource());
        dto.setReceivedAt(event.getReceivedAt());
        dto.setEventCreatedAt(event.getEventCreatedAt());
        dto.setPayloadHash(event.getPayloadHash());
        dto.setProcessingStatus(event.getProcessingStatus());
        dto.setProcessedAt(event.getProcessedAt());
        dto.setErrorMessage(event.getErrorMessage());
        dto.setMerchantId(event.getMerchantId());
        dto.setPublishStatus(event.getPublishStatus());
        dto.setPublishedAt(event.getPublishedAt());
        return dto;
    }

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

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public WebhookPublishStatus getPublishStatus() {
        return publishStatus;
    }

    public void setPublishStatus(WebhookPublishStatus publishStatus) {
        this.publishStatus = publishStatus;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
