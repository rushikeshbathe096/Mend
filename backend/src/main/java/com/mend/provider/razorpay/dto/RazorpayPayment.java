package com.mend.provider.razorpay.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * Razorpay payment entity (documented field names). Customer fields (email, contact, vpa, card) are deliberately
 * not mapped. {@code notes} may be an object or an empty array, so it stays a {@link JsonNode}.
 */
public record RazorpayPayment(
        String id,
        String entity,
        Long amount,
        String currency,
        String status,
        @JsonProperty("order_id") String orderId,
        Boolean captured,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_reason") String errorReason,
        JsonNode notes) {
}
