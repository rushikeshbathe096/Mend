package com.mend.evaluation.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * M4 U4.3: what a snapshot covers. Campaigns whose first eligibility is in [windowFrom, windowTo) and at or before asOf;
 * every fact as of {@code asOf}. Outcome horizon: capture within {@code horizonDays} of first eligibility.
 *
 * @param m4Start      optional configured M4 start: first eligibility at or after it is "post-M4" (assignment required);
 *                     each merchant's first assignment also starts M4 for that merchant (the earlier of the two applies)
 * @param providerMode mock | razorpay-test | razorpay-live | razorpay-unknown | simulator
 */
public record SnapshotParams(Instant windowFrom, Instant windowTo, Instant asOf, int horizonDays, Instant m4Start,
                             String providerMode, List<UUID> merchantFilter) {

    public static final int DEFAULT_HORIZON_DAYS = 14;

    public SnapshotParams {
        Objects.requireNonNull(windowFrom, "windowFrom");
        Objects.requireNonNull(windowTo, "windowTo");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(providerMode, "providerMode");
        // PostgreSQL keeps microseconds: truncate so a stored snapshot's parameters compare equal on re-runs
        windowFrom = windowFrom.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        windowTo = windowTo.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        asOf = asOf.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!windowFrom.isBefore(windowTo)) {
            throw new IllegalArgumentException("window must be non-empty: " + windowFrom + " .. " + windowTo);
        }
        if (horizonDays < 1 || horizonDays > 365) {
            throw new IllegalArgumentException("horizonDays must be 1..365");
        }
        merchantFilter = merchantFilter == null ? List.of() : merchantFilter.stream().sorted().toList();
    }

    /**
     * DECISION U4.3-f (invariant 20): only Razorpay live mode yields MEASURED rows. Mock, simulator and Razorpay test mode
     * (provider evidence, but test money) and an unknown key mode are SYNTHETIC: never revenue.
     */
    public boolean synthetic() {
        return !"razorpay-live".equals(providerMode);
    }
}
