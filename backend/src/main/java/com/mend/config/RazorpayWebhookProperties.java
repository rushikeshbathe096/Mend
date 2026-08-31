package com.mend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RazorpayWebhookProperties {

    private final String secret;

    public RazorpayWebhookProperties(@Value("${razorpay.webhook.secret:}") String secret) {
        this.secret = secret;
    }

    public String getSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("RAZORPAY_WEBHOOK_SECRET configuration is missing or blank");
        }
        return secret.trim();
    }

    public boolean isSecretConfigured() {
        return secret != null && !secret.isBlank();
    }
}
