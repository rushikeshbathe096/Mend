package com.mend.domain.enums;

public enum ActionIntentStatus {
    PENDING,
    SCHEDULED,
    READY,
    CLAIMED,
    PROCESSING,
    EXECUTING,
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
}
