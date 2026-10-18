package com.mend.evaluation.snapshot;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * M4 U4.3 (ADR-004 §5, invariant 23): stable per-merchant pseudonyms. {@code HMAC-SHA256(merchant EVAL_ID salt, kind + ":" +
 * value)}, first 16 bytes as hex. Without the merchant's salt a reference cannot be linked back to a raw id.
 */
public final class Pseudonyms {

    private final Map<UUID, byte[]> saltsByMerchant;

    public Pseudonyms(Map<UUID, byte[]> saltsByMerchant) {
        this.saltsByMerchant = Map.copyOf(saltsByMerchant);
    }

    public String merchantRef(UUID merchantId) {
        return ref(merchantId, "merchant", "self");
    }

    public String ref(UUID merchantId, String kind, String value) {
        if (value == null) {
            return null;
        }
        byte[] salt = saltsByMerchant.get(merchantId);
        if (salt == null) {
            throw new IllegalStateException("no EVAL_ID salt for merchant " + merchantId);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] digest = mac.doFinal((kind + ":" + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
