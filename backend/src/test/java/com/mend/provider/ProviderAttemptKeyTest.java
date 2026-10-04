package com.mend.provider;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProviderAttemptKeyTest {

    @Test
    void forIntentIsDeterministicForSameUuid() {
        UUID intentId = UUID.randomUUID();
        assertEquals(ProviderAttemptKey.forIntent(intentId), ProviderAttemptKey.forIntent(intentId));
    }

    @Test
    void forIntentHasExpectedShapeAndFitsReferenceLimit() {
        UUID intentId = UUID.fromString("3F575AEB-94C4-42D4-ACA0-3A0F0641064C");
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intentId);
        assertEquals("mnd_3f575aeb94c442d4aca03a0f0641064c", key.value());
        assertEquals(36, key.value().length());
        assertTrue(key.value().length() <= 40);
    }

    @Test
    void distinctUuidsGiveDistinctKeys() {
        assertNotEquals(ProviderAttemptKey.forIntent(UUID.randomUUID()), ProviderAttemptKey.forIntent(UUID.randomUUID()));
    }

    @Test
    void rejectsColon() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderAttemptKey("intent:abc"));
    }

    @Test
    void rejectsUppercase() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderAttemptKey("MND_abc"));
    }

    @Test
    void rejectsFortyOneCharacters() {
        assertDoesNotThrow(() -> new ProviderAttemptKey("a".repeat(40)));
        assertThrows(IllegalArgumentException.class, () -> new ProviderAttemptKey("a".repeat(41)));
    }

    @Test
    void rejectsNullAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderAttemptKey(null));
        assertThrows(IllegalArgumentException.class, () -> new ProviderAttemptKey(""));
        assertThrows(IllegalArgumentException.class, () -> ProviderAttemptKey.forIntent(null));
    }
}
