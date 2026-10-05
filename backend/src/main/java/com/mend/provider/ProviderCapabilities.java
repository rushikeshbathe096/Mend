package com.mend.provider;

import com.mend.domain.enums.ActionType;

/** What the active provider can do. The real provider supports no merchant-initiated debit until M2. */
public interface ProviderCapabilities {

    boolean supportsRecurringDebit();

    /**
     * True only when re-sending the same attempt (same {@link ProviderAttemptKey}) can never create a second provider
     * resource: the provider itself rejects the duplicate. Gates the reconciler's SAFE_RETRY (M2 invariant 5).
     * Defaults to false (fail safe): a provider must opt in per action type.
     */
    default boolean supportsIdempotentResend(ActionType actionType) {
        return false;
    }
}
