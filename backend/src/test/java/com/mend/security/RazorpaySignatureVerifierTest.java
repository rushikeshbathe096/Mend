package com.mend.security;

import com.mend.config.RazorpayWebhookProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RazorpaySignatureVerifierTest {

    private RazorpaySignatureVerifier verifier;
    private static final String TEST_SECRET = "test_webhook_secret_12345";

    @BeforeEach
    void setUp() {
        RazorpayWebhookProperties properties = new RazorpayWebhookProperties(TEST_SECRET);
        verifier = new RazorpaySignatureVerifier(properties);
    }

    @Test
    void verifySignature_ValidSignature_ReturnsTrue() {
        String payload = "{\"event\":\"payment.failed\",\"account_id\":\"acc_123\"}";
        String expectedSignature = verifier.calculateHmacSha256(payload, TEST_SECRET);

        assertTrue(verifier.verifySignature(payload, expectedSignature));
    }

    @Test
    void verifySignature_InvalidSignature_ReturnsFalse() {
        String payload = "{\"event\":\"payment.failed\",\"account_id\":\"acc_123\"}";
        String invalidSignature = "invalid_hex_signature_1234567890abcdef";

        assertFalse(verifier.verifySignature(payload, invalidSignature));
    }

    @Test
    void verifySignature_NullOrEmptySignature_ReturnsFalse() {
        String payload = "{\"event\":\"payment.failed\"}";

        assertFalse(verifier.verifySignature(payload, null));
        assertFalse(verifier.verifySignature(payload, ""));
        assertFalse(verifier.verifySignature(payload, "   "));
    }

    @Test
    void verifySignature_TamperedPayload_ReturnsFalse() {
        String originalPayload = "{\"event\":\"payment.failed\",\"amount\":1000}";
        String signature = verifier.calculateHmacSha256(originalPayload, TEST_SECRET);

        String tamperedPayload = "{\"event\":\"payment.failed\",\"amount\":9999}";

        assertFalse(verifier.verifySignature(tamperedPayload, signature));
    }

    @Test
    void verifySignature_WrongSecret_ReturnsFalse() {
        String payload = "{\"event\":\"payment.failed\"}";
        String signatureWithWrongSecret = verifier.calculateHmacSha256(payload, "wrong_secret");

        assertFalse(verifier.verifySignature(payload, signatureWithWrongSecret));
    }

    @Test
    void constantTimeEquals_MatchingStrings_ReturnsTrue() {
        assertTrue(verifier.constantTimeEquals("abcdef123456", "abcdef123456"));
    }

    @Test
    void constantTimeEquals_MismatchedStrings_ReturnsFalse() {
        assertFalse(verifier.constantTimeEquals("abcdef123456", "abcdef123457"));
        assertFalse(verifier.constantTimeEquals("abcdef123456", "abcdef12345"));
        assertFalse(verifier.constantTimeEquals(null, "abcdef123456"));
    }
}
