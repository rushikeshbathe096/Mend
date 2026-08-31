package com.mend.domain.enums;

public enum WebhookEventStatus {
    RECEIVED,
    VERIFIED,
    PROCESSING,
    PROCESSED,
    FAILED,
    IGNORED,
    INVALID_SIGNATURE
}
