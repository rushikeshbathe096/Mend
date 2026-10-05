package com.mend.provider;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PaymentEvidenceTest {

    @Test
    void optionalFieldsMayBeNull() {
        PaymentEvidence evidence = new PaymentEvidence("pay_1", PaymentEvidenceStatus.CAPTURED, 100, "INR",
                null, null, null, Instant.EPOCH);
        assertNull(evidence.orderId());
    }

    @Test
    void rejectsNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentEvidence("pay_1", PaymentEvidenceStatus.FAILED, -1, "INR",
                null, null, null, Instant.EPOCH));
    }

    @Test
    void requiredComponentsAreNullChecked() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentEvidence(null, PaymentEvidenceStatus.FAILED, 1, "INR",
                null, null, null, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new PaymentEvidence("pay_1", null, 1, "INR",
                null, null, null, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new PaymentEvidence("pay_1", PaymentEvidenceStatus.FAILED, 1, null,
                null, null, null, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new PaymentEvidence("pay_1", PaymentEvidenceStatus.FAILED, 1, "INR",
                null, null, null, null));
    }
}
