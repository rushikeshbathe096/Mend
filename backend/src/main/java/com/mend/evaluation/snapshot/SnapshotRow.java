package com.mend.evaluation.snapshot;

/**
 * M4 U4.3: one eligible campaign in an evaluation snapshot (schema {@link #SCHEMA_VERSION}). PII-free: references are
 * per-merchant HMAC pseudonyms, time is the UTC eligibility day plus offsets in seconds from first eligibility, amounts
 * are exact minor units from the strict parser (null = unknown). Field order here is the canonical file order.
 *
 * @param recovered      provider capture evidence within the horizon (and at or before as_of)
 * @param mature         eligibility + horizon is at or before as_of (only mature rows enter outcome figures)
 * @param captureSource  MEND | ORIGINAL | SYNTHETIC | UNKNOWN of the first capture within the horizon, or null
 */
public record SnapshotRow(
        String schemaVersion,
        String merchantRef,
        String campaignRef,
        String invoiceRef,
        String customerRef,
        String eligibleDay,
        String arm,
        Integer holdoutRateBp,
        Integer propensityBp,
        String assignmentSource,
        Integer saltVersion,
        String hashVersion,
        boolean postM4,
        String failureClass,
        String taxonomyVersion,
        String strategy,
        String compliance,
        String complianceReason,
        boolean contacted,
        int contactCount,
        Long firstContactOffsetS,
        boolean mature,
        boolean recovered,
        Long captureOffsetS,
        String captureSource,
        String recoveryAttribution,
        Long amountMinor,
        String currency,
        String amountStatus,
        String stateAsOf,
        int legacyDecisionBlocks,
        String evidenceLabel) {

    public static final String SCHEMA_VERSION = "eval-snapshot-v1";
    public static final String UNASSIGNED = "UNASSIGNED";
}
