package com.mend.provider.razorpay.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Response of {@code GET /v1/payment_links?reference_id=...}. */
public record RazorpayPaymentLinkList(@JsonProperty("payment_links") List<RazorpayPaymentLink> paymentLinks) {
}
