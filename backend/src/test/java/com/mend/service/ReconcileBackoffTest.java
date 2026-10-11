package com.mend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("M2 U5: reconcile backoff (full jitter, seeded)")
class ReconcileBackoffTest {

    private final ReconcileBackoff backoff = new ReconcileBackoff(Duration.ofSeconds(30), Duration.ofHours(6), new SplittableRandom(42));

    @Test
    void ceilingDoublesFromBaseUntilTheCap() {
        assertEquals(Duration.ofSeconds(30), backoff.ceiling(0));
        assertEquals(Duration.ofSeconds(60), backoff.ceiling(1));
        assertEquals(Duration.ofSeconds(240), backoff.ceiling(3));
        assertEquals(Duration.ofSeconds(15360), backoff.ceiling(9));
        assertEquals(Duration.ofHours(6), backoff.ceiling(10));
        assertEquals(Duration.ofHours(6), backoff.ceiling(1_000_000));
    }

    @Test
    void delayStaysWithinFloorAndCeilingAndIsJittered() {
        for (int attempts = 0; attempts < 40; attempts++) {
            Duration ceiling = backoff.ceiling(attempts);
            java.util.Set<Duration> seen = new java.util.HashSet<>();
            for (int i = 0; i < 200; i++) {
                Duration delay = backoff.delay(attempts);
                assertTrue(delay.compareTo(ReconcileBackoff.FLOOR) >= 0, "delay below floor: " + delay);
                assertTrue(delay.compareTo(ceiling) <= 0, "delay " + delay + " above ceiling " + ceiling);
                seen.add(delay);
            }
            assertTrue(seen.size() > 1, "full jitter must vary the delay");
        }
    }

    @Test
    void sameSeedSameSequence() {
        ReconcileBackoff a = new ReconcileBackoff(Duration.ofSeconds(30), Duration.ofHours(6), new SplittableRandom(7));
        ReconcileBackoff b = new ReconcileBackoff(Duration.ofSeconds(30), Duration.ofHours(6), new SplittableRandom(7));
        for (int i = 0; i < 20; i++) {
            assertEquals(a.delay(i), b.delay(i));
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ReconcileBackoff(Duration.ZERO, Duration.ofHours(1), new SplittableRandom(1)));
        assertThrows(IllegalArgumentException.class, () -> new ReconcileBackoff(Duration.ofHours(2), Duration.ofHours(1), new SplittableRandom(1)));
    }
}
