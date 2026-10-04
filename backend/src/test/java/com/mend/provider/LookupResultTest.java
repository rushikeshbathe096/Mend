package com.mend.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LookupResultTest {

    // No default branch: this only compiles while the hierarchy is sealed and every case is covered.
    private static String describe(LookupResult<String> result) {
        return switch (result) {
            case LookupResult.Found<String> found -> "found:" + found.value();
            case LookupResult.NotFound<String> notFound -> "not-found";
            case LookupResult.Unavailable<String> unavailable -> "unavailable";
        };
    }

    @Test
    void exhaustiveSwitchCoversEveryResult() {
        assertEquals("found:x", describe(new LookupResult.Found<>("x")));
        assertEquals("not-found", describe(new LookupResult.NotFound<>()));
        assertEquals("unavailable", describe(new LookupResult.Unavailable<>("503", 503)));
    }

    @Test
    void requiredComponentsAreNullChecked() {
        assertThrows(IllegalArgumentException.class, () -> new LookupResult.Found<String>(null));
        assertThrows(IllegalArgumentException.class, () -> new LookupResult.Unavailable<String>(null, null));
    }

    @Test
    void notFoundIsNotFound() {
        assertTrue(new LookupResult.Found<>("x").isFound());
        assertFalse(new LookupResult.NotFound<String>().isFound());
    }
}
