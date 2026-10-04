package com.mend.provider;

public enum RejectionKind {
    VALIDATION,
    AUTHENTICATION,
    NOT_SUPPORTED,
    RATE_LIMITED,
    DUPLICATE_REFERENCE,
    LOCAL_PRECONDITION
}
