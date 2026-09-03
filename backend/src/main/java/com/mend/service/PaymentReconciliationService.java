package com.mend.service;

import com.mend.domain.entity.WebhookEvent;

public interface PaymentReconciliationService {
    ReconciliationResult reconcileWebhookEvent(WebhookEvent event);
}
