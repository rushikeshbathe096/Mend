package com.mend.service;

import com.mend.events.WebhookEventMessage;

/**
 * Clean interface for downstream webhook event processing boundary.
 * Phase 5 provides this extension point for Phase 6 AI Classification to plug into seamlessly.
 */
public interface EventProcessingService {
    /**
     * Processes a deserialized and verified WebhookEventMessage.
     *
     * @param eventMessage the event contract payload
     * @return true if processing succeeded, false otherwise
     */
    boolean processEvent(WebhookEventMessage eventMessage);
}
