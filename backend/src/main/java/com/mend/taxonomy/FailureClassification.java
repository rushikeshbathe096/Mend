package com.mend.taxonomy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of {@link FailureTaxonomy#classify}: the class, which field decided it ({@code REASON}, {@code SOURCE} or
 * {@code NONE}), the matched value, the documentation the rule is taken from, and the table version. No confidence.
 */
public record FailureClassification(PaymentFailureClass failureClass, MatchedOn matchedOn, String matchedValue,
                                    String documentation, String tableVersion) {

    public enum MatchedOn { REASON, SOURCE, NONE }

    /** The stored evidence of this classification: what matched, the cited doc, and the provider fields it read. */
    public Map<String, Object> evidence(ProviderFailure failure) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("matchedOn", matchedOn.name());
        if (matchedValue != null) evidence.put("matchedValue", matchedValue);
        if (documentation != null) evidence.put("documentation", documentation);
        if (failure != null) {
            if (failure.code() != null) evidence.put("errorCode", failure.code());
            if (failure.reason() != null) evidence.put("errorReason", failure.reason());
            if (failure.source() != null) evidence.put("errorSource", failure.source());
        }
        return evidence;
    }
}
