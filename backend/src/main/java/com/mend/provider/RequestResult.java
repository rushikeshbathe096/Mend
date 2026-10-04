package com.mend.provider;

/**
 * Outcome of a state-changing provider request: did the provider accept our command?
 * Says nothing about money; see {@link PaymentEvidence} for that.
 */
public sealed interface RequestResult<T> {

    record Accepted<T>(T value) implements RequestResult<T> {
        public Accepted {
            if (value == null) {
                throw new IllegalArgumentException("value is required");
            }
        }
    }

    /** The provider (or a local precondition) definitively refused the command. Code, reason and status may be absent. */
    record Rejected<T>(RejectionKind kind, String providerCode, String providerReason, Integer httpStatus)
            implements RequestResult<T> {
        public Rejected {
            if (kind == null) {
                throw new IllegalArgumentException("kind is required");
            }
        }
    }

    /** The request provably never left MEND, so resending it is safe. */
    record NotSent<T>(String cause) implements RequestResult<T> {
        public NotSent {
            if (cause == null) {
                throw new IllegalArgumentException("cause is required");
            }
        }
    }

    /** The request may have reached the provider but we do not know what happened. Look up before any resend. */
    record OutcomeUnknown<T>(String cause, Integer httpStatus) implements RequestResult<T> {
        public OutcomeUnknown {
            if (cause == null) {
                throw new IllegalArgumentException("cause is required");
            }
        }
    }

    default boolean isAccepted() {
        return this instanceof Accepted<T>;
    }
}
