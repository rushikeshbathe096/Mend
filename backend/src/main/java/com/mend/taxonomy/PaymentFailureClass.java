package com.mend.taxonomy;

/**
 * M3 U2.1: deterministic class of a failed Razorpay payment, derived only from the provider's documented error fields
 * (ADR-003). Never produced by a model, never carries a confidence.
 */
public enum PaymentFailureClass {
    /** The customer's account lacks funds. */
    INSUFFICIENT_FUNDS,
    /** A card, credit, daily or frequency limit was hit (documented limit family; added with evidence). */
    LIMIT_EXCEEDED,
    /** The issuing / customer's bank or PSP had a technical problem or was unavailable. */
    BANK_TECHNICAL,
    /** The gateway or Razorpay failed (technical error, timeout, high traffic). */
    GATEWAY_ERROR,
    /** 3DS / OTP / PIN / CVV authentication failed. */
    AUTHENTICATION_FAILURE,
    /** The customer cancelled, abandoned or let the payment time out, or their PSP app declined it. */
    CUSTOMER_DECLINED,
    /** The payment instrument cannot be used (expired, blocked, inactive; added with evidence). */
    INSTRUMENT_INVALID,
    /** A recurring mandate could not be created or acknowledged. */
    MANDATE_INVALID,
    /** No documented mapping: never guessed. */
    UNCLASSIFIED;

    /**
     * The class named {@code value}, or null when {@code value} is not a taxonomy class (null, or a pre-M3 AI-era
     * class such as {@code BANK_DECLINED} / {@code UNKNOWN} on a historical row).
     */
    public static PaymentFailureClass fromStored(String value) {
        if (value == null) {
            return null;
        }
        for (PaymentFailureClass c : values()) {
            if (c.name().equals(value)) {
                return c;
            }
        }
        return null;
    }

    /** True only for a taxonomy class other than UNCLASSIFIED: legacy and unclassified values fail closed. */
    public static boolean isRecoverable(String value) {
        PaymentFailureClass c = fromStored(value);
        return c != null && c != UNCLASSIFIED;
    }
}
