package com.mend.provider;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CreatePaymentLinkTest {

    @Test
    void carriesOnlyNonCustomerFieldsSoToStringCannotLeakPii() {
        List<String> components = Arrays.stream(CreatePaymentLink.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("key", "amount", "description", "expireBy", "intentId"), components);
    }

    @Test
    void toStringOmitsDescriptionText() {
        UUID intentId = UUID.randomUUID();
        CreatePaymentLink command = new CreatePaymentLink(ProviderAttemptKey.forIntent(intentId),
                new MoneyMinor(100, "INR"), "free-text-that-must-not-be-logged", null, intentId);
        String text = command.toString();
        assertFalse(text.contains("free-text-that-must-not-be-logged"));
        assertTrue(text.contains(intentId.toString()));
    }

    @Test
    void rejectsBlankDescription() {
        UUID intentId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new CreatePaymentLink(ProviderAttemptKey.forIntent(intentId),
                new MoneyMinor(100, "INR"), "  ", null, intentId));
    }

    @Test
    void expireByIsOptional() {
        UUID intentId = UUID.randomUUID();
        CreatePaymentLink command = new CreatePaymentLink(ProviderAttemptKey.forIntent(intentId),
                new MoneyMinor(100, "INR"), "MEND recovery", null, intentId);
        assertNull(command.expireBy());
    }

    @Test
    void requiredComponentsAreNullChecked() {
        UUID intentId = UUID.randomUUID();
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intentId);
        MoneyMinor amount = new MoneyMinor(100, "INR");
        assertThrows(IllegalArgumentException.class, () -> new CreatePaymentLink(null, amount, "d", null, intentId));
        assertThrows(IllegalArgumentException.class, () -> new CreatePaymentLink(key, null, "d", null, intentId));
        assertThrows(IllegalArgumentException.class, () -> new CreatePaymentLink(key, amount, null, null, intentId));
        assertThrows(IllegalArgumentException.class, () -> new CreatePaymentLink(key, amount, "d", null, null));
    }
}
