package com.mend.evaluation.sim;

import com.mend.taxonomy.ProviderFailure;

import java.time.Instant;
import java.util.UUID;

/**
 * M4 U4.5: one failed payment to replay. Either the provider's failure fields (generated streams: the production taxonomy
 * classifies them) or an already stored class (snapshot replay). SYNTHETIC by construction.
 *
 * @param storedClass used only when {@code failure} is null (replay of a snapshot row)
 */
public record ReplayEvent(UUID merchantId, UUID campaignId, String invoiceKey, ProviderFailure failure, String storedClass,
                          long amountMinor, Instant eligibleAt) {
}
