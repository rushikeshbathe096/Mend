package com.mend.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Isolated test database (pre-M3 hygiene)")
class IsolatedTestDatabaseTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_791_700_000L);

    @Test
    @DisplayName("Only this class's own names older than 24 h are stale; the dev database and foreign names never are")
    void staleRule() {
        String old = "mend_test_" + NOW.minus(Duration.ofHours(25)).getEpochSecond() + "_deadbeef";
        String fresh = "mend_test_" + NOW.minus(Duration.ofHours(23)).getEpochSecond() + "_deadbeef";
        assertTrue(IsolatedTestDatabase.isStale(old, NOW));
        assertFalse(IsolatedTestDatabase.isStale(fresh, NOW), "another run may still be using it");
        for (String other : new String[] {"mend", "mend_test", "postgres", "mend_test_" + old, "mend_test_123_deadbeef",
                "mend_test_1791600000_DEADBEEF", "mend_test_1791600000_deadbeef; DROP DATABASE mend"}) {
            assertFalse(IsolatedTestDatabase.isStale(other, NOW), other);
        }
    }

    @Test
    @DisplayName("This run is using its own database, never the dev database")
    void thisRunUsesItsOwnDatabase() {
        String url = System.getProperty(IsolatedTestDatabase.URL_PROPERTY);
        assertNotNull(url, "the extension sets the URL before any test class runs");
        assertTrue(url.matches("^jdbc:postgresql://.+/mend_test_\\d{10}_[0-9a-f]{8}$"), url);
    }
}
