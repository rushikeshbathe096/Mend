package com.mend.events;

import java.time.Instant;
import java.util.UUID;

public record WebhookEventMessage(
        UUID eventId,
        String externalEventId,
        UUID merchantId,
        String eventType,
        Instant occurredAt,
        String payloadHash
) {}
