package com.mend.provider.razorpay;

public enum TransportFailureKind {
    /** The request provably never left MEND (DNS failure, connection refused, connect timeout). */
    NOT_SENT,
    /** The request may have been sent; no usable response arrived. */
    SENT_NO_RESPONSE
}
