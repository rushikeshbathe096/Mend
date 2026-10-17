package com.mend.taxonomy;

/**
 * Result of {@link FailureTaxonomy#classify}: the class, which field decided it ({@code REASON}, {@code SOURCE} or
 * {@code NONE}), the matched value, the documentation the rule is taken from, and the table version. No confidence.
 */
public record FailureClassification(PaymentFailureClass failureClass, MatchedOn matchedOn, String matchedValue,
                                    String documentation, String tableVersion) {

    public enum MatchedOn { REASON, SOURCE, NONE }
}
