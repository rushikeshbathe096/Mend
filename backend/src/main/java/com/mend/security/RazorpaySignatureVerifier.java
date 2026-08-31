package com.mend.security;

import com.mend.config.RazorpayWebhookProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class RazorpaySignatureVerifier {

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    private final RazorpayWebhookProperties webhookProperties;

    public RazorpaySignatureVerifier(RazorpayWebhookProperties webhookProperties) {
        this.webhookProperties = webhookProperties;
    }

    public boolean verifySignature(String rawPayload, String providedSignature) {
        if (rawPayload == null || providedSignature == null || providedSignature.isBlank()) {
            return false;
        }

        String secret = webhookProperties.getSecret();
        String expectedSignature = calculateHmacSha256(rawPayload, secret);

        return constantTimeEquals(expectedSignature, providedSignature.trim());
    }

    public String calculateHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM);
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    /**
     * Constant-time equality comparison to prevent timing attacks.
     */
    public boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
