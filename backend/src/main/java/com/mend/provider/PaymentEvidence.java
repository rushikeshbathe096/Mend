package com.mend.provider;

import java.time.Instant;

/** Provider-reported state of a single payment. {@code orderId}, {@code errorCode} and {@code errorReason} may be null. */
public record PaymentEvidence(
        String providerPaymentId,
        PaymentEvidenceStatus status,
        long amountMinor,
        String currency,
        String orderId,
        String errorCode,
        String errorReason,
        Instant observedAt) {

    public PaymentEvidence {
        if (providerPaymentId == null) {
            throw new IllegalArgumentException("providerPaymentId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt is required");
        }
    }
}
