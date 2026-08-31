package com.mend.domain.enums;

public enum CampaignStatus {
    CREATED,
    CLASSIFIED,
    ELIGIBLE,
    SCHEDULED,
    ACTION_PENDING,
    EXECUTING,
    RECOVERED,
    EXHAUSTED,
    FAILED,
    CANCELLED,

    // Legacy values retained for backward compatibility
    STRATEGY_ASSIGNED,
    WAITING,
    ACTION_EXECUTING,
    RETRY_SCHEDULED,
    PENDING_REVIEW,
    ESCALATED,
    MANDATE_INVALID,
    STOPPED
}
