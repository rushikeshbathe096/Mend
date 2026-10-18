package com.mend.evaluation.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** M4 U4.3: one decision_log row as read (raw ids, in memory only). */
public record DecisionFacts(
        UUID merchantId,
        UUID campaignId,
        UUID intentId,
        String decisionPoint,
        String chosen,
        String reasonCode,
        List<String> alternatives,
        int propensityBp,
        String taxonomyVersion,
        String strategyVersion,
        String complianceVersion,
        String assignmentVersion,
        Instant decidedAt) {

    public DecisionFacts {
        alternatives = List.copyOf(alternatives);
    }
}
