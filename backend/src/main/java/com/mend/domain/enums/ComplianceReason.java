package com.mend.domain.enums;

public enum ComplianceReason {
    ALLOWED,
    INVALID_CAMPAIGN_STATE,
    MAX_ATTEMPTS_EXCEEDED,
    LOW_CONFIDENCE,
    UNKNOWN_CLASSIFICATION,
    STRATEGY_NOT_SUPPORTED,
    MERCHANT_POLICY_BLOCKED,
    DUPLICATE_ACTION,
    TENANT_MISMATCH,
    MISSING_CONTEXT,
    /** M3 U2.4: the customer opted out of contact (reply policy); fail closed until a merchant admin clears it. */
    CUSTOMER_OPTED_OUT
}
