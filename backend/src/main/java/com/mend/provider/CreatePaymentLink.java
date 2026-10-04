package com.mend.provider;

import java.time.Instant;
import java.util.UUID;

/**
 * Command to create a payment link. Carries no customer data in M1; keep it that way so
 * {@link #toString()} can never leak PII. {@code expireBy} may be null.
 */
public record CreatePaymentLink(
        ProviderAttemptKey key,
        MoneyMinor amount,
        String description,
        Instant expireBy,
        UUID intentId) {

    public CreatePaymentLink {
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (description == null) {
            throw new IllegalArgumentException("description is required");
        }
        if (intentId == null) {
            throw new IllegalArgumentException("intentId is required");
        }
    }
}
