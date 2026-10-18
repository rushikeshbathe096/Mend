package com.mend.domain.enums;

/** The closed set of intents a reply suggestion may carry (ADR-003 §5; V20 check constraint). */
public enum ReplyIntent {
    OPT_OUT,
    ALREADY_PAID,
    DISPUTE,
    PROMISE_TO_PAY,
    OTHER
}
