package com.mend.provider;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic key sent to the provider so every re-execution of the same ActionIntent
 * maps to the same provider-side object. At most 40 characters of {@code [a-z0-9_]}.
 */
public record ProviderAttemptKey(String value) {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{1,40}$");

    public ProviderAttemptKey {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid provider attempt key");
        }
    }

    /** {@code "mnd_" + 32 lowercase hex digits of the intent UUID} = 36 characters. */
    public static ProviderAttemptKey forIntent(UUID intentId) {
        if (intentId == null) {
            throw new IllegalArgumentException("intentId is required");
        }
        return new ProviderAttemptKey("mnd_" + intentId.toString().replace("-", ""));
    }
}
