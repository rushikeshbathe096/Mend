package com.mend.domain.enums;

public enum ActionIntentStatus {
    PENDING,
    SCHEDULED,
    READY,
    CLAIMED,
    EXECUTING,
    /** M2: provider outcome unknown or worker presumed dead; resolved only from provider evidence (reconciler). */
    NEEDS_RECONCILIATION,
    /** M2: payment link issued and live; waiting for captured evidence or link expiry/cancellation. */
    AWAITING_PAYMENT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }

    public boolean isExecutable() {
        return this == PENDING || this == SCHEDULED || this == READY;
    }

    /** At most one in-flight intent per (merchant, invoice) — M2-execution-safety.md §4/§6. */
    public boolean isInFlight() {
        return this == CLAIMED || this == EXECUTING || this == NEEDS_RECONCILIATION || this == AWAITING_PAYMENT;
    }
}
