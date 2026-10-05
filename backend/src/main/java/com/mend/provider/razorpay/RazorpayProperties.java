package com.mend.provider.razorpay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Razorpay settings. Reuses the existing property names ({@code razorpay.api.base-url}, {@code razorpay.key-id}, ...).
 * {@code singleTenantMerchantId} is the only MEND merchant whose actions may use the env credentials.
 */
@ConfigurationProperties("razorpay")
public record RazorpayProperties(
        @DefaultValue Api api,
        String keyId,
        String keySecret,
        @DefaultValue("5000") int connectTimeoutMs,
        @DefaultValue("10000") int readTimeoutMs,
        String singleTenantMerchantId) {

    public record Api(@DefaultValue("https://api.razorpay.com") String baseUrl) {
    }

    /** Never prints the key secret. */
    @Override
    public String toString() {
        return "RazorpayProperties[baseUrl=" + baseUrl() + ", keyId=" + keyId + ", keySecret=****, connectTimeoutMs="
                + connectTimeoutMs + ", readTimeoutMs=" + readTimeoutMs + ", singleTenantMerchantId=" + singleTenantMerchantId + "]";
    }

    public String baseUrl() {
        String url = api.baseUrl() == null || api.baseUrl().isBlank() ? "https://api.razorpay.com" : api.baseUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
