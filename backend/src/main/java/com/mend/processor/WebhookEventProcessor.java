package com.mend.processor;

import com.mend.events.WebhookEventMessage;

public interface WebhookEventProcessor {
    boolean process(WebhookEventMessage message);
}
