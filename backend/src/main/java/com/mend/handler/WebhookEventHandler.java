package com.mend.handler;

import com.mend.dto.event.WebhookEventEnvelope;

public interface WebhookEventHandler {
    boolean handle(WebhookEventEnvelope envelope);
}
