package com.mend.provider;

/** Outcome of a read-only provider lookup. Reads are safe to retry. */
public sealed interface LookupResult<T> {

    record Found<T>(T value) implements LookupResult<T> {
        public Found {
            if (value == null) {
                throw new IllegalArgumentException("value is required");
            }
        }
    }

    record NotFound<T>() implements LookupResult<T> {
    }

    /** The provider could not be asked or did not answer. Not the same as {@link NotFound}. */
    record Unavailable<T>(String cause, Integer httpStatus) implements LookupResult<T> {
        public Unavailable {
            if (cause == null) {
                throw new IllegalArgumentException("cause is required");
            }
        }
    }

    default boolean isFound() {
        return this instanceof Found<T>;
    }
}
