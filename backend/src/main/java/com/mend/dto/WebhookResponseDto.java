package com.mend.dto;

import java.time.Instant;
import java.util.UUID;

public class WebhookResponseDto {

    private UUID id;
    private String externalEventId;
    private String status;
    private String message;
    private Instant receivedAt;

    public WebhookResponseDto() {
    }

    public WebhookResponseDto(UUID id, String externalEventId, String status, String message, Instant receivedAt) {
        this.id = id;
        this.externalEventId = externalEventId;
        this.status = status;
        this.message = message;
        this.receivedAt = receivedAt;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}
