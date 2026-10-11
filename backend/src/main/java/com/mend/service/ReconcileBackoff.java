package com.mend.service;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Reconcile retry delay with full jitter: {@code uniform(0, min(cap, base * 2^attempts))}, floored at one second so a row
 * that does not progress is always pushed into the future (no hot loop). The random source is injectable for tests.
 */
public final class ReconcileBackoff {

    static final Duration FLOOR = Duration.ofSeconds(1);

    private final Duration base;
    private final Duration cap;
    private final RandomGenerator random;

    public ReconcileBackoff(Duration base, Duration cap, RandomGenerator random) {
        if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("backoff needs 0 < base <= cap, was base=" + base + " cap=" + cap);
        }
        this.base = base;
        this.cap = cap;
        this.random = random;
    }

    /** Upper bound of the jitter window after {@code attempts} previous reconcile attempts. */
    public Duration ceiling(int attempts) {
        long capMillis = cap.toMillis();
        long ceilingMillis = base.toMillis();
        for (int i = 0; i < attempts && ceilingMillis < capMillis; i++) {
            ceilingMillis = ceilingMillis > capMillis / 2 ? capMillis : ceilingMillis * 2;
        }
        return Duration.ofMillis(Math.min(ceilingMillis, capMillis));
    }

    public synchronized Duration delay(int attempts) {
        long jittered = random.nextLong(ceiling(attempts).toMillis() + 1);
        return Duration.ofMillis(Math.max(jittered, FLOOR.toMillis()));
    }
}
