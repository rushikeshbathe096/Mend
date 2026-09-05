package com.mend.dto.event;

import com.mend.events.WebhookEventMessage;

public record WebhookOutboxPayload(
        WebhookEventMessage message,
        WebhookEventEnvelope envelope
) {
}
