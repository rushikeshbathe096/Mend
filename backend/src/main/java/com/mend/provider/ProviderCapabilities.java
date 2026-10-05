package com.mend.provider;

/** What the active provider can do. The real provider supports no merchant-initiated debit until M2. */
public interface ProviderCapabilities {

    boolean supportsRecurringDebit();
}
