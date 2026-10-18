package com.mend.evaluation.sim;

import com.mend.taxonomy.ProviderFailure;

import java.time.Instant;
import java.util.UUID;

/**
 * M4 U4.5: one failed payment to replay. Either the provider's failure fields (generated streams: the production taxonomy
 * classifies them) or an already stored class with the taxonomy version it was stored with (snapshot replay: a row without
 * a taxonomy version is legacy and fails closed, as in production). SYNTHETIC by construction.
 */
public record ReplayEvent(UUID merchantId, UUID campaignId, String invoiceKey, ProviderFailure failure, String storedClass,
                          String storedTaxonomyVersion, long amountMinor, Instant eligibleAt) {
}
