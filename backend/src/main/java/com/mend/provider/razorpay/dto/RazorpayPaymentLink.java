package com.mend.provider.razorpay.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Payment link entity (documented field names). {@code customer} and {@code notes} are not mapped: Razorpay
 * returns them as objects or empty arrays. {@code payments} may be null (recorded fixture 01) or empty.
 */
public record RazorpayPaymentLink(
        String id,
        Long amount,
        @JsonProperty("amount_paid") Long amountPaid,
        String currency,
        @JsonProperty("reference_id") String referenceId,
        String status,
        @JsonProperty("short_url") String shortUrl,
        List<Payment> payments,
        @JsonProperty("order_id") String orderId) {

    /** Item of a link's {@code payments[]}. */
    public record Payment(@JsonProperty("payment_id") String paymentId, String status, Long amount) {
    }
}
