package com.mend.publisher;

import com.mend.domain.entity.WebhookEvent;

public interface WebhookEventPublisher {
    void publish(WebhookEvent event);
}
