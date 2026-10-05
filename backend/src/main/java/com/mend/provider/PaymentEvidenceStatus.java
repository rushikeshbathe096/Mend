package com.mend.provider;

/** What happened to the money, as evidenced by the provider. */
public enum PaymentEvidenceStatus {
    /** The only status that establishes recovery. */
    CAPTURED,
    /** Not recovery; the provider may auto-refund. */
    AUTHORIZED_NOT_CAPTURED,
    PENDING,
    FAILED,
    REFUNDED,
    /** Provider sent a status we do not know. Never treated as recovered. */
    UNRECOGNIZED;

    public boolean isRecovery() {
        return this == CAPTURED;
    }
}
