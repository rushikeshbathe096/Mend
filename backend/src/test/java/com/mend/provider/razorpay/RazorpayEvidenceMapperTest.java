package com.mend.provider.razorpay;

import com.mend.provider.PaymentEvidence;
import com.mend.provider.PaymentEvidenceStatus;
import com.mend.provider.PaymentLinkState;
import com.mend.provider.razorpay.dto.RazorpayPayment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RazorpayEvidenceMapperTest {

    private final RazorpayEvidenceMapper mapper = new RazorpayEvidenceMapper();

    @ParameterizedTest
    @CsvSource({
            "captured, CAPTURED",
            "authorized, AUTHORIZED_NOT_CAPTURED",
            "created, PENDING",
            "failed, FAILED",
            "refunded, REFUNDED",
            "'  CAPTURED ', CAPTURED",
            "Authorized, AUTHORIZED_NOT_CAPTURED"
    })
    void mapsEveryDocumentedPaymentStatusCaseInsensitively(String raw, PaymentEvidenceStatus expected) {
        assertEquals(expected, mapper.mapPaymentStatus(raw));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"late_authorized", "active", "success", "paid", "captured\n<script>", "x_status_value_that_is_much_longer_than_forty_characters"})
    void unknownPaymentStatusIsUnrecognizedNeverRecovery(String raw) {
        PaymentEvidenceStatus status = mapper.mapPaymentStatus(raw);
        assertEquals(PaymentEvidenceStatus.UNRECOGNIZED, status);
        assertFalse(status.isRecovery());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void unrecognizedStatusIsLoggedAtWarnSanitized(org.springframework.boot.test.system.CapturedOutput output) {
        mapper.mapPaymentStatus("weird\nvalue");
        assertTrue(output.getOut().contains("event=unrecognized_provider_status kind=payment value=weird?value"));
    }

    @Test
    void authorizedIsNotRecovery() {
        assertFalse(mapper.mapPaymentStatus("authorized").isRecovery());
        assertTrue(mapper.mapPaymentStatus("captured").isRecovery());
    }

    @ParameterizedTest
    @CsvSource({
            "paid, PAID",
            "partially_paid, PARTIALLY_PAID",
            "created, CREATED",
            "expired, EXPIRED",
            "cancelled, CANCELLED",
            "' PAID ', PAID"
    })
    void mapsEveryDocumentedLinkStatus(String raw, PaymentLinkState expected) {
        assertEquals(expected, mapper.mapLinkStatus(raw));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"canceled", "captured", "issued"})
    void unknownLinkStatusIsUnrecognized(String raw) {
        assertEquals(PaymentLinkState.UNRECOGNIZED, mapper.mapLinkStatus(raw));
    }

    @Test
    void toEvidenceCopiesFieldsAndMapsStatus() throws Exception {
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        PaymentEvidence evidence = mapper.toEvidence(new RazorpayPayment("pay_1", "payment", 49900L, "INR", "failed",
                "order_1", false, "BAD_REQUEST_ERROR", "payment_cancelled", null), now);
        assertEquals(new PaymentEvidence("pay_1", PaymentEvidenceStatus.FAILED, 49900, "INR", "order_1",
                "BAD_REQUEST_ERROR", "payment_cancelled", now), evidence);
    }

    @Test
    void toEvidenceRejectsMissingRequiredFields() {
        Instant now = Instant.EPOCH;
        assertThrows(MalformedProviderResponse.class, () -> mapper.toEvidence(null, now));
        assertThrows(MalformedProviderResponse.class, () -> mapper.toEvidence(payment(null, 100L, "INR"), now));
        assertThrows(MalformedProviderResponse.class, () -> mapper.toEvidence(payment(" ", 100L, "INR"), now));
        assertThrows(MalformedProviderResponse.class, () -> mapper.toEvidence(payment("pay_1", null, "INR"), now));
        assertThrows(MalformedProviderResponse.class, () -> mapper.toEvidence(payment("pay_1", -1L, "INR"), now));
        assertThrows(MalformedProviderResponse.class, () -> mapper.toEvidence(payment("pay_1", 100L, null), now));
        assertThrows(MalformedProviderResponse.class, () -> mapper.toEvidence(payment("pay_1", 100L, ""), now));
    }

    private static RazorpayPayment payment(String id, Long amount, String currency) {
        return new RazorpayPayment(id, "payment", amount, currency, "captured", null, true, null, null, null);
    }
}
