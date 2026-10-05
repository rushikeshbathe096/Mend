package com.mend.provider;

public enum RejectionKind {
    VALIDATION,
    AUTHENTICATION,
    NOT_SUPPORTED,
    RATE_LIMITED,
    /**
     * A provider object with our reference already exists but does not match the command
     * (e.g. different amount). Only produced after a lookup by reference, never directly from a 400.
     */
    DUPLICATE_REFERENCE,
    LOCAL_PRECONDITION
}
