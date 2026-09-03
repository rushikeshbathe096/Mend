package com.mend.events;

import java.time.Instant;
import java.util.UUID;

public record WebhookEventMessage(
        UUID eventId,
        String externalEventId,
        UUID merchantId,
        String eventType,
        Instant occurredAt,
        String payloadHash,
        String version,
        String rawPayload
) {
    public static final String DEFAULT_VERSION = "WEBHOOK_EVENT_V1";

    public WebhookEventMessage(UUID eventId, String externalEventId, UUID merchantId, String eventType, Instant occurredAt, String payloadHash) {
        this(eventId, externalEventId, merchantId, eventType, occurredAt, payloadHash, DEFAULT_VERSION, null);
    }

    public WebhookEventMessage(UUID eventId, String externalEventId, UUID merchantId, String eventType, Instant occurredAt, String payloadHash, String version) {
        this(eventId, externalEventId, merchantId, eventType, occurredAt, payloadHash, version, null);
    }
}

