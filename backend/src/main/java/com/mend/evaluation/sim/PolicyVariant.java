package com.mend.evaluation.sim;

/**
 * M4 U4.5: the policies the simulator compares. Each runs the production engines; a variant only changes the inputs or
 * gates around them, never the engines' code.
 * <ul>
 *   <li>{@code B_CURRENT}: production as it is (owner decision B: an UNCLASSIFIED decline gets a payment link).</li>
 *   <li>{@code A_UNCLASSIFIED_INELIGIBLE}: the pre-decision-B rule (ADR-003 §4 before 2026-10-18): an UNCLASSIFIED failure
 *       is ineligible, so no action. Applied as an eligibility gate in front of the production engines.</li>
 * </ul>
 */
public enum PolicyVariant {
    B_CURRENT,
    A_UNCLASSIFIED_INELIGIBLE
}
