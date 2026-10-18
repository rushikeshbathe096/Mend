package com.mend.domain.enums;

/** A customer reply's parsing state (V20): PENDING until the job stores a suggestion (PARSED) or gives up (FAILED). */
public enum ReplyStatus {
    PENDING,
    PARSED,
    FAILED
}
