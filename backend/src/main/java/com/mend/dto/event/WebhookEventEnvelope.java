package com.mend.dto.event;

import java.time.Instant;
import java.util.UUID;

public class WebhookEventEnvelope {

    private String eventId;
    private int schemaVersion = 1;
    private String provider;
    private String providerEventId;
    private String eventType;
    private UUID merchantId;
    private UUID webhookDatabaseId;
    private Instant occurredAt;
    private Instant receivedAt;
    private String payloadHash;

    public WebhookEventEnvelope() {
    }

    public WebhookEventEnvelope(String eventId, int schemaVersion, String provider, String providerEventId,
                                String eventType, UUID merchantId, UUID webhookDatabaseId,
                                Instant occurredAt, Instant receivedAt, String payloadHash) {
        this.eventId = eventId;
        this.schemaVersion = schemaVersion;
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.eventType = eventType;
        this.merchantId = merchantId;
        this.webhookDatabaseId = webhookDatabaseId;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.payloadHash = payloadHash;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public void setProviderEventId(String providerEventId) {
        this.providerEventId = providerEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public UUID getWebhookDatabaseId() {
        return webhookDatabaseId;
    }

    public void setWebhookDatabaseId(UUID webhookDatabaseId) {
        this.webhookDatabaseId = webhookDatabaseId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }
}
