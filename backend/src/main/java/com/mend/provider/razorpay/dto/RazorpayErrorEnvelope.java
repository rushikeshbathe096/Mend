package com.mend.provider.razorpay.dto;

import tools.jackson.databind.JsonNode;

/** Razorpay error body: {@code {"error":{"code","description","source","step","reason","metadata"}}}. */
public record RazorpayErrorEnvelope(Error error) {

    public record Error(String code, String description, String source, String step, String reason, JsonNode metadata) {
    }
}
