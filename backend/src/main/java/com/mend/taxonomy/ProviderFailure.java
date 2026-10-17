package com.mend.taxonomy;

/**
 * The error fields of a failed Razorpay payment entity ({@code error_code}, {@code error_reason}, {@code error_source}).
 * Values are trimmed; blank becomes null (unknown), whichever way the record is built.
 */
public record ProviderFailure(String code, String reason, String source) {

    public ProviderFailure {
        code = blankToNull(code);
        reason = blankToNull(reason);
        source = blankToNull(source);
    }

    public static ProviderFailure of(String code, String reason, String source) {
        return new ProviderFailure(code, reason, source);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
