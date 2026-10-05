package com.mend.provider.razorpay.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /v1/payment_links}. Exactly the documented fields MEND sends; Razorpay rejects unknown
 * fields with 400 "extra fields sent" (recorded fixture 03). No {@code customer} object in M1.
 * {@code expireBy} is UNIX seconds and omitted when null (NON_NULL serialization).
 */
public record RazorpayCreatePaymentLinkRequest(
        long amount,
        String currency,
        @JsonProperty("reference_id") String referenceId,
        String description,
        @JsonProperty("expire_by") Long expireBy,
        @JsonProperty("notify") RazorpayLinkNotify linkNotify,
        @JsonProperty("reminder_enable") boolean reminderEnable,
        Notes notes) {

    public record Notes(
            @JsonProperty("mend_intent_id") String mendIntentId,
            @JsonProperty("mend_merchant_id") String mendMerchantId) {
    }
}
