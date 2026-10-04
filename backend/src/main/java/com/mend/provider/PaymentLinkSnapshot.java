package com.mend.provider;

import java.util.List;

/**
 * Provider-reported state of a payment link. A link's state is not payment evidence:
 * recovery is established only by a {@link PaymentEvidence} with status {@code CAPTURED}.
 * {@code referenceId} is null when the link carries no reference; {@code shortUrl} may be null.
 */
public record PaymentLinkSnapshot(
        String providerLinkId,
        ProviderAttemptKey referenceId,
        PaymentLinkState state,
        long amountMinor,
        long amountPaidMinor,
        String currency,
        String shortUrl,
        List<String> capturedPaymentIds) {

    public PaymentLinkSnapshot {
        if (providerLinkId == null) {
            throw new IllegalArgumentException("providerLinkId is required");
        }
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (capturedPaymentIds == null) {
            throw new IllegalArgumentException("capturedPaymentIds is required");
        }
        capturedPaymentIds = List.copyOf(capturedPaymentIds);
    }
}
