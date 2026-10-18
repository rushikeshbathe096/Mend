package com.mend.experiment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * M4 U4.1 (ADR-004 §1): deterministic randomized holdout assignment. Pure Java (no Spring, JPA or other MEND packages).
 * <pre>
 *   value  = first 8 bytes of HMAC-SHA256(key = merchant salt, message = UTF-8 invoice_key), unsigned 64-bit
 *   bucket = value mod 10 000                       (uniform over 0..9999)
 *   arm    = CONTROL iff bucket &lt; holdout rate (basis points), else TREATMENT
 * </pre>
 * The same salt and invoice key always give the same arm; a rate of 0 gives TREATMENT for every key.
 */
public final class HoldoutAssigner {

    public static final String HASH_VERSION = "hmac-sha256-u64-mod10000-v1";
    public static final int BUCKETS = 10_000;
    /** ADR-004 §2: MEND never withholds recovery from more than half of a merchant's campaigns. */
    public static final int MAX_RATE_BP = 5_000;
    public static final int SALT_BYTES = 32;

    private HoldoutAssigner() {
    }

    public static int bucket(byte[] salt, String invoiceKey) {
        Objects.requireNonNull(invoiceKey, "invoiceKey");
        if (salt == null || salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("salt must be " + SALT_BYTES + " bytes");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            long value = ByteBuffer.wrap(mac.doFinal(invoiceKey.getBytes(StandardCharsets.UTF_8))).getLong();
            return (int) Long.remainderUnsigned(value, BUCKETS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static Arm arm(int bucket, int holdoutRateBp) {
        requireRate(holdoutRateBp);
        if (bucket < 0 || bucket >= BUCKETS) {
            throw new IllegalArgumentException("bucket out of range: " + bucket);
        }
        return bucket < holdoutRateBp ? Arm.CONTROL : Arm.TREATMENT;
    }

    /** Probability of the arm, in basis points: the rate for CONTROL, 10 000 minus the rate for TREATMENT. */
    public static int propensityBp(Arm arm, int holdoutRateBp) {
        requireRate(holdoutRateBp);
        return switch (arm) {
            case CONTROL -> holdoutRateBp;
            case TREATMENT -> BUCKETS - holdoutRateBp;
        };
    }

    public static void requireRate(int holdoutRateBp) {
        if (holdoutRateBp < 0 || holdoutRateBp > MAX_RATE_BP) {
            throw new IllegalArgumentException("holdout rate must be 0.." + MAX_RATE_BP + " bp: " + holdoutRateBp);
        }
    }
}
