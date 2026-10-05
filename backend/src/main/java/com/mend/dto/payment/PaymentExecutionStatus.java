package com.mend.dto.payment;

/** Executor report status. {@code PENDING}: request accepted or outcome unknown; money not established. */
public enum PaymentExecutionStatus {
    SUCCESS,
    FAILURE,
    ERROR,
    PENDING
}
