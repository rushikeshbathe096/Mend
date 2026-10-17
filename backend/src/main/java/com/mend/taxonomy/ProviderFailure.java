package com.mend.taxonomy;

/** The error fields of a failed Razorpay payment entity (`error_code`, `error_reason`, `error_source`); any may be null. */
public record ProviderFailure(String code, String reason, String source) {

    public static ProviderFailure of(String code, String reason, String source) {
        return new ProviderFailure(blankToNull(code), blankToNull(reason), blankToNull(source));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
