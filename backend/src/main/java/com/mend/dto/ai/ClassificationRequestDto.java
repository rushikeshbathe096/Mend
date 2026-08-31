package com.mend.dto.ai;

import java.util.UUID;

public record ClassificationRequestDto(
    String eventId,
    String eventType,
    String failureCode,
    String failureReason,
    String provider,
    String merchantId
) {
    public static ClassificationRequestDto of(UUID eventId, String eventType, String failureCode, String failureReason, String provider, UUID merchantId) {
        return new ClassificationRequestDto(
            eventId != null ? eventId.toString() : null,
            eventType,
            failureCode,
            failureReason,
            provider != null ? provider : "RAZORPAY",
            merchantId != null ? merchantId.toString() : null
        );
    }
}
