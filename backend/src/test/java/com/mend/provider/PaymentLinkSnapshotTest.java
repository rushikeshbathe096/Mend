package com.mend.provider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentLinkSnapshotTest {

    @Test
    void capturedPaymentIdsAreDefensivelyCopiedAndImmutable() {
        List<String> ids = new ArrayList<>(List.of("pay_1"));
        PaymentLinkSnapshot snapshot = new PaymentLinkSnapshot("plink_1", ProviderAttemptKey.forIntent(UUID.randomUUID()),
                PaymentLinkState.PAID, 100, 100, "INR", "https://rzp.io/x", ids);

        ids.add("pay_2");

        assertEquals(List.of("pay_1"), snapshot.capturedPaymentIds());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.capturedPaymentIds().add("pay_3"));
    }

    @Test
    void rejectsNullPaymentIdElementWithIllegalArgument() {
        List<String> ids = new ArrayList<>();
        ids.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentLinkSnapshot("plink_1", null, PaymentLinkState.PAID, 100, 100, "INR", null, ids));
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentLinkSnapshot("plink_1", null, PaymentLinkState.CREATED, -1, 0, "INR", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentLinkSnapshot("plink_1", null, PaymentLinkState.CREATED, 100, -1, "INR", null, List.of()));
    }

    @Test
    void requiredComponentsAreNullChecked() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentLinkSnapshot(null, null, PaymentLinkState.CREATED, 100, 0, "INR", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentLinkSnapshot("plink_1", null, null, 100, 0, "INR", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentLinkSnapshot("plink_1", null, PaymentLinkState.CREATED, 100, 0, "INR", null, null));
    }
}
