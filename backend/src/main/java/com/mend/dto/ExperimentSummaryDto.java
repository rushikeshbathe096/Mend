package com.mend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M4 U4.8(c): a merchant's experiment view. Live arm counts are operational (not causal). Causal figures come only from the
 * latest VALID evaluation snapshot (AGENTS.md invariant 23) and carry their label (MEASURED / SYNTHETIC / TBD) and n.
 */
public record ExperimentSummaryDto(int holdoutRateBp, long assignedTreatment, long assignedControl, Snapshot snapshot,
                                   List<FigureDto> figures, String note) {

    public record Snapshot(UUID id, Instant asOf, int horizonDays, String providerMode, String label, boolean interim) {
    }

    public record FigureDto(String stratum, String name, String value, String low, String high, long n, String label, String status,
                            String note) {
    }
}
