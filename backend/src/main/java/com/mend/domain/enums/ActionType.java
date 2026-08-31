package com.mend.domain.enums;

public enum ActionType {
    RETRY_PAYMENT,
    REQUEST_CUSTOMER_ACTION,
    MANUAL_REVIEW;

    public static ActionType fromRecoveryStrategy(RecoveryStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        return switch (strategy) {
            case RETRY_IMMEDIATELY, RETRY_LATER -> RETRY_PAYMENT;
            case CUSTOMER_ACTION_REQUIRED -> REQUEST_CUSTOMER_ACTION;
            case MANUAL_REVIEW -> MANUAL_REVIEW;
            case NO_ACTION -> null;
        };
    }
}
