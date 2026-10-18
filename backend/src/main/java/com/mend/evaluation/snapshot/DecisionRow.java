package com.mend.evaluation.snapshot;

import java.util.List;

/** M4 U4.3: one decision-log row in a snapshot (pseudonymous; time as seconds from the campaign's first eligibility). */
public record DecisionRow(
        String schemaVersion,
        String merchantRef,
        String campaignRef,
        String intentRef,
        String decisionPoint,
        String chosen,
        String reasonCode,
        List<String> alternatives,
        int propensityBp,
        String taxonomyVersion,
        String strategyVersion,
        String complianceVersion,
        String assignmentVersion,
        long offsetS) {

    public DecisionRow {
        alternatives = List.copyOf(alternatives);
    }
}
