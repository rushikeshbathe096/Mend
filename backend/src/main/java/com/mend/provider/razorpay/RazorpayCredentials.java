package com.mend.provider.razorpay;

/** Razorpay API key pair. The secret never appears in {@link #toString()}. */
public record RazorpayCredentials(String keyId, String keySecret) {

    public RazorpayCredentials {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new IllegalArgumentException("keyId and keySecret are required");
        }
    }

    @Override
    public String toString() {
        return "RazorpayCredentials[keyId=" + keyId + ", keySecret=****]";
    }
}
