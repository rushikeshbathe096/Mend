package com.mend.provider.mock;

import com.mend.provider.ProviderAttemptKey;

import java.util.UUID;

/** One call recorded by {@link MockPaymentProvider}. {@code campaignId}/{@code intentId} may be null for reads. */
public record MockProviderCall(String operation, UUID merchantId, UUID campaignId, UUID intentId, ProviderAttemptKey attemptKey) {
}
