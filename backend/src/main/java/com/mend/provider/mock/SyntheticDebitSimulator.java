package com.mend.provider.mock;

import com.mend.provider.MerchantRef;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.RequestResult;

import java.util.UUID;

/**
 * Mock-mode only: simulates a merchant-initiated debit so dev/demo flows keep working. Results are SYNTHETIC.
 * {@code Accepted} carries a synthetic payment id. No implementation exists in Razorpay mode.
 */
public interface SyntheticDebitSimulator {

    RequestResult<String> simulateDebit(MerchantRef merchant, UUID campaignId, UUID intentId, ProviderAttemptKey key);
}
