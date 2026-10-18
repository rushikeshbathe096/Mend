package com.mend.evaluation.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * M4 U4.3: what the extractor (or the simulator) knows about one eligible campaign as of the snapshot's {@code as_of}.
 * Raw ids and timestamps live only here, in memory; {@link SnapshotBuilder} turns them into PII-free {@link SnapshotRow}s.
 *
 * @param assignment     null for a campaign never assigned (pre-M4)
 * @param dispatchTimes  provider dispatches (contacts) at or before as_of
 * @param captures       provider capture evidence at or before as_of (any order)
 * @param amountMinor    from the strict minor-unit parser (the failed payment's amount: money figures assume the invoice is
 *                       captured in full), or null when unknown
 * @param merchantM4Start the merchant's first holdout assignment at or before as_of (null = the merchant never ran M4)
 * @param syntheticSource the failure came from a simulated source (demo webhooks), so the row is SYNTHETIC
 */
public record CampaignFacts(
        UUID merchantId,
        UUID campaignId,
        String invoiceKey,
        String customerKey,
        Instant eligibleAt,
        Assignment assignment,
        String failureClass,
        String taxonomyVersion,
        String strategy,
        String compliance,
        String complianceReason,
        List<Instant> dispatchTimes,
        List<Capture> captures,
        String recoveryAttribution,
        String stateAsOf,
        Long amountMinor,
        String currency,
        int legacyDecisionBlocks,
        Instant merchantM4Start,
        boolean syntheticSource) {

    public CampaignFacts {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(eligibleAt, "eligibleAt");
        dispatchTimes = dispatchTimes == null ? List.of() : List.copyOf(dispatchTimes);
        captures = captures == null ? List.of() : List.copyOf(captures);
    }

    /** The stored holdout assignment (holdout_assignments row). */
    public record Assignment(String arm, int holdoutRateBp, int propensityBp, String source, int saltVersion, String hashVersion) {
    }

    /** Capture evidence: when, and whose (MEND link, ORIGINAL payment, SYNTHETIC mock capture, UNKNOWN attribution). */
    public record Capture(Instant at, CaptureSource source) {
    }

    public enum CaptureSource { MEND, ORIGINAL, SYNTHETIC, UNKNOWN }
}
