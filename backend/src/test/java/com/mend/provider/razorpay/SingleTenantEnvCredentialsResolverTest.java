package com.mend.provider.razorpay;

import com.mend.provider.MerchantRef;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SingleTenantEnvCredentialsResolverTest {

    private static final UUID MERCHANT = UUID.fromString("8d919a7f-f12f-4bb2-8900-5be3df0d6a84");

    private static SingleTenantEnvCredentialsResolver resolver(String merchantId, String keyId, String secret) {
        return new SingleTenantEnvCredentialsResolver(
                new RazorpayProperties(new RazorpayProperties.Api(null), keyId, secret, 5000, 10000, merchantId));
    }

    @Test
    void configuredMerchantGetsCredentials() {
        RazorpayCredentials creds = resolver(MERCHANT.toString(), "rzp_test_k", "s").resolve(new MerchantRef(MERCHANT)).orElseThrow();
        assertEquals("rzp_test_k", creds.keyId());
    }

    @Test
    void otherMerchantGetsNothing() {
        assertTrue(resolver(MERCHANT.toString(), "rzp_test_k", "s").resolve(new MerchantRef(UUID.randomUUID())).isEmpty());
    }

    @Test
    void blankConfiguredMerchantFailsClosed() {
        assertTrue(resolver("", "rzp_test_k", "s").resolve(new MerchantRef(MERCHANT)).isEmpty());
        assertTrue(resolver(null, "rzp_test_k", "s").resolve(new MerchantRef(MERCHANT)).isEmpty());
        assertTrue(resolver("not-a-uuid", "rzp_test_k", "s").resolve(new MerchantRef(MERCHANT)).isEmpty());
    }

    @Test
    void blankSecretOrKeyFailsClosed() {
        assertTrue(resolver(MERCHANT.toString(), "rzp_test_k", " ").resolve(new MerchantRef(MERCHANT)).isEmpty());
        assertTrue(resolver(MERCHANT.toString(), "", "s").resolve(new MerchantRef(MERCHANT)).isEmpty());
    }

    @Test
    void credentialsToStringMasksSecret() {
        String text = new RazorpayCredentials("rzp_test_k", "very_secret").toString();
        assertFalse(text.contains("very_secret"));
        assertTrue(text.contains("rzp_test_k"));
    }
}
