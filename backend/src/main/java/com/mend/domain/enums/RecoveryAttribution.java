package com.mend.domain.enums;

/**
 * M2 U8: who brought the money back for a RECOVERED campaign. Null on campaigns recovered before V15 (unknown).
 * Only provider evidence ({@code captured}) sets MEND or ORIGINAL_PAYMENT_LATE_CAPTURE (invariant 2); SYNTHETIC is the
 * mock provider's simulated capture and is never written in razorpay mode.
 */
public enum RecoveryAttribution {
    /** Captured payment on a MEND recovery action (intent-specific correlation). */
    MEND,
    /** The original failed payment was captured later; MEND did not recover it. */
    ORIGINAL_PAYMENT_LATE_CAPTURE,
    /** Bug sweep item 5 (V18): mock-mode SyntheticCaptured. Simulated money: reported separately, never as MEND. */
    SYNTHETIC
}
