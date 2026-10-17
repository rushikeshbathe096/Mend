package com.mend.dto.event;

import com.mend.domain.entity.WebhookEvent;
import com.mend.events.WebhookEventMessage;

import java.time.Instant;

public record WebhookOutboxPayload(
        WebhookEventMessage message,
        WebhookEventEnvelope envelope
) {

    /** The outbox payload for a verified webhook: the stream message and its envelope, built from the stored event. */
    public static WebhookOutboxPayload forWebhook(WebhookEvent event) {
        Instant occurredAt = event.getEventCreatedAt() != null ? event.getEventCreatedAt() : event.getReceivedAt();
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }

        WebhookEventMessage message = new WebhookEventMessage(
                event.getId(),
                event.getExternalEventId(),
                event.getMerchantId(),
                event.getEventType(),
                occurredAt,
                event.getPayloadHash(),
                WebhookEventMessage.DEFAULT_VERSION,
                event.getRawPayload()
        );
        WebhookEventEnvelope envelope = new WebhookEventEnvelope(
                event.getExternalEventId(),
                1,
                event.getSource() != null ? event.getSource() : "RAZORPAY",
                event.getExternalEventId(),
                event.getEventType(),
                event.getMerchantId(),
                event.getId(),
                occurredAt,
                event.getReceivedAt() != null ? event.getReceivedAt() : occurredAt,
                event.getPayloadHash()
        );
        return new WebhookOutboxPayload(message, envelope);
    }
}
