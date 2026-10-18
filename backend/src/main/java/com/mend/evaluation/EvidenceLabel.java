package com.mend.evaluation;

/**
 * M4 (AGENTS.md invariant 20): every evaluation number is labelled. MEASURED = from real provider evidence; SYNTHETIC =
 * simulator, generated data or the mock provider (never revenue); TBD = not computed or not computable.
 */
public enum EvidenceLabel {
    MEASURED,
    SYNTHETIC,
    TBD
}
