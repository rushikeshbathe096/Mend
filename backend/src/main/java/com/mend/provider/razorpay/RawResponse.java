package com.mend.provider.razorpay;

/** What came back from one HTTP exchange: an HTTP reply of any status, or a transport failure. */
public sealed interface RawResponse {

    long latencyMs();

    record HttpReply(int status, String body, long latencyMs) implements RawResponse {
    }

    /** {@code cause} is a short machine code, never a raw exception message. */
    record TransportFailure(TransportFailureKind kind, String cause, long latencyMs) implements RawResponse {
    }
}
