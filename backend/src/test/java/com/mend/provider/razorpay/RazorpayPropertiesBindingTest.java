package com.mend.provider.razorpay;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class RazorpayPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(RazorpayProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void bindsExistingPropertyNames() {
        runner.withPropertyValues(
                        "razorpay.api.base-url=https://example.test/",
                        "razorpay.key-id=rzp_test_k",
                        "razorpay.key-secret=s",
                        "razorpay.connect-timeout-ms=1234",
                        "razorpay.read-timeout-ms=5678",
                        "razorpay.single-tenant-merchant-id=8d919a7f-f12f-4bb2-8900-5be3df0d6a84",
                        "razorpay.webhook.secret=ignored")
                .run(context -> {
                    RazorpayProperties props = context.getBean(RazorpayProperties.class);
                    assertEquals("https://example.test", props.baseUrl());
                    assertEquals("rzp_test_k", props.keyId());
                    assertEquals(1234, props.connectTimeoutMs());
                    assertEquals(5678, props.readTimeoutMs());
                    assertEquals("8d919a7f-f12f-4bb2-8900-5be3df0d6a84", props.singleTenantMerchantId());
                });
    }

    @Test
    void toStringMasksKeySecret() {
        String text = new RazorpayProperties(new RazorpayProperties.Api(null), "rzp_test_k", "very_secret", 1, 1, null).toString();
        assertFalse(text.contains("very_secret"));
        assertTrue(text.contains("rzp_test_k"));
    }

    @Test
    void defaultsMatchApplicationProperties() {
        runner.run(context -> {
            RazorpayProperties props = context.getBean(RazorpayProperties.class);
            assertEquals("https://api.razorpay.com", props.baseUrl());
            assertEquals(5000, props.connectTimeoutMs());
            assertEquals(10000, props.readTimeoutMs());
        });
    }
}
