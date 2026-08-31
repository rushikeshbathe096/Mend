package com.mend.domain.enums;

public enum RecoveryStrategy {
    RETRY_IMMEDIATELY,
    RETRY_LATER,
    CUSTOMER_ACTION_REQUIRED,
    NO_ACTION,
    MANUAL_REVIEW
}
