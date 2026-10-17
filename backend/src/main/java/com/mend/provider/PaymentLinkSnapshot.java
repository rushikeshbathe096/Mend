package com.mend.provider;

import java.util.List;
import java.util.Objects;

/**
 * Provider-reported state of a payment link. A link's state is not payment evidence:
 * recovery is established only by a {@link PaymentEvidence} with status {@code CAPTURED}.
 * {@code referenceId} is null when the link carries no reference. Adapters must treat a reference that is not a
 * valid {@link ProviderAttemptKey} as a malformed response rather than letting construction throw.
 * {@code shortUrl} may be null.
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
        if (amountMinor < 0 || amountPaidMinor < 0) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (capturedPaymentIds == null || capturedPaymentIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("capturedPaymentIds is required");
        }
        capturedPaymentIds = List.copyOf(capturedPaymentIds);
    }

    /**
     * True when a stored provider reference names a payment link (provider link ids start with {@code plink_}), so it can
     * be looked up by id; anything else (null, a payment id, a debit reference) is looked up by attempt key instead.
     */
    public static boolean isLinkId(String providerReference) {
        return providerReference != null && providerReference.startsWith("plink_");
    }
}
