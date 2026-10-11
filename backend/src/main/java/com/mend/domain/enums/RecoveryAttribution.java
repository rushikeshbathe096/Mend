package com.mend.domain.enums;

/**
 * M2 U8: who brought the money back for a RECOVERED campaign. Null on campaigns recovered before V15 (unknown).
 * Only provider evidence ({@code captured}) ever sets either value (invariant 2).
 */
public enum RecoveryAttribution {
    /** Captured payment on a MEND recovery action (intent-specific correlation). */
    MEND,
    /** The original failed payment was captured later; MEND did not recover it. */
    ORIGINAL_PAYMENT_LATE_CAPTURE
}
