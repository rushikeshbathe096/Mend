package com.mend.service;

import com.mend.provider.RejectionKind;

/**
 * M2 U9 (ADR-002 decision 10): what a failed dispatch means for the recovery.
 * <ul>
 *   <li>{@link #DEFER}: the problem is MEND's and nothing reached the provider (not sent, rate limited, no credentials, amount
 *       not resolved). Retry later; after the cap a human fixes it. Never EXHAUSTED.</li>
 *   <li>{@link #REVIEW}: the action cannot run automatically (debit not enabled, manual review). A human decides. Never EXHAUSTED.</li>
 *   <li>{@link #TERMINAL}: the provider or the data says no (validation, other local preconditions, duplicate reference).</li>
 * </ul>
 * Outcomes that are not failures (link issued, synthetic capture, unknown) are {@link #NONE}: Unknown is reconciliation's job.
 */
public enum OutcomeRetryPolicy {
    NONE, DEFER, REVIEW, TERMINAL;

    static final String NO_CREDENTIALS = "NO_CREDENTIALS_FOR_MERCHANT";
    static final String AMOUNT_UNRESOLVED = "AMOUNT_UNRESOLVED";

    public static OutcomeRetryPolicy classify(ProviderActionOutcome outcome) {
        return switch (outcome) {
            case ProviderActionOutcome.LinkIssued ignored -> NONE;
            case ProviderActionOutcome.SyntheticCaptured ignored -> NONE;
            case ProviderActionOutcome.Unknown ignored -> NONE;
            case ProviderActionOutcome.NotSent ignored -> DEFER;
            case ProviderActionOutcome.Rejected rejected -> classifyRejection(rejected.kind(), rejected.code());
            // No NotSupported reason today is a MEND-side availability cause; all of them need a human.
            case ProviderActionOutcome.NotSupported notSupported -> switch (notSupported.reason()) {
                case ProviderActionDispatcher.RECURRING_DEBIT_NOT_ENABLED, ProviderActionDispatcher.MANUAL_REVIEW_NOT_AUTOMATED -> REVIEW;
                default -> TERMINAL;
            };
        };
    }

    private static OutcomeRetryPolicy classifyRejection(RejectionKind kind, String code) {
        return switch (kind) {
            case RATE_LIMITED -> DEFER;
            case AUTHENTICATION -> NO_CREDENTIALS.equals(code) ? DEFER : TERMINAL;
            case LOCAL_PRECONDITION -> AMOUNT_UNRESOLVED.equals(code) ? DEFER : TERMINAL;
            case VALIDATION, NOT_SUPPORTED, DUPLICATE_REFERENCE -> TERMINAL;
        };
    }

    /** Short code for audits and review reasons (ids and codes only). */
    public static String code(ProviderActionOutcome outcome) {
        return switch (outcome) {
            case ProviderActionOutcome.NotSent notSent -> "NOT_SENT:" + notSent.cause();
            case ProviderActionOutcome.Rejected rejected -> rejected.code() != null ? rejected.code() : rejected.kind().name();
            case ProviderActionOutcome.NotSupported notSupported -> notSupported.reason();
            case ProviderActionOutcome.LinkIssued ignored -> "LINK_ISSUED";
            case ProviderActionOutcome.SyntheticCaptured ignored -> "SYNTHETIC_CAPTURED";
            case ProviderActionOutcome.Unknown unknown -> "UNKNOWN:" + unknown.cause();
        };
    }
}
