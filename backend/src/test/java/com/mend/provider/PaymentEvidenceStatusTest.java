package com.mend.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaymentEvidenceStatusTest {

    @Test
    void onlyCapturedIsRecovery() {
        for (PaymentEvidenceStatus status : PaymentEvidenceStatus.values()) {
            assertEquals(status == PaymentEvidenceStatus.CAPTURED, status.isRecovery(), status.name());
        }
    }
}
