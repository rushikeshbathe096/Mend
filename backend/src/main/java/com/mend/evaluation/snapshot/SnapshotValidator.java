package com.mend.evaluation.snapshot;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * M4 U4.3 (ADR-004 §5): checks a campaigns file as written. Nothing is repaired: every defect is reported and the snapshot
 * is INVALID. Works on the parsed JSON, so a fractional or negative amount in a file is caught even though the strict
 * parser can never produce one.
 */
public final class SnapshotValidator {

    public record Defect(String code, String campaignRef, String detail) {
    }

    private static final Set<String> ARMS = Set.of("TREATMENT", "CONTROL", SnapshotRow.UNASSIGNED);
    private static final Set<String> LABELS = Set.of("MEASURED", "SYNTHETIC");
    private static final Set<String> CAPTURE_SOURCES = Set.of("MEND", "ORIGINAL", "SYNTHETIC", "UNKNOWN");
    private static final Pattern DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern REF = Pattern.compile("^[0-9a-f]{32}$");

    private SnapshotValidator() {
    }

    private static final Set<String> POINTS = Set.of("ASSIGNMENT", "STRATEGY", "COMPLIANCE", "DISPATCH");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** Campaign rows only (no horizon check). */
    public static List<Defect> validate(String campaignsJsonl) {
        return validate(campaignsJsonl, "", null);
    }

    /** Both files; {@code horizonDays} (when known) bounds capture offsets. */
    public static List<Defect> validate(String campaignsJsonl, String decisionsJsonl, Integer horizonDays) {
        List<Defect> defects = campaigns(campaignsJsonl, horizonDays);
        Set<String> refs = new HashSet<>();
        try {
            SnapshotCodec.lines(campaignsJsonl).forEach(r -> refs.add(text(r, "campaign_ref")));
        } catch (RuntimeException alreadyReported) {
            return defects;
        }
        decisions(decisionsJsonl, refs, defects);
        return defects;
    }

    private static List<Defect> campaigns(String campaignsJsonl, Integer horizonDays) {
        List<Defect> defects = new ArrayList<>();
        List<JsonNode> rows;
        try {
            rows = SnapshotCodec.lines(campaignsJsonl);
        } catch (RuntimeException unparsable) {
            defects.add(new Defect("SCHEMA_UNPARSABLE", null, unparsable.getClass().getSimpleName()));
            return defects;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode r : rows) {
            String ref = r.path("campaign_ref").isString() ? r.path("campaign_ref").stringValue() : null;
            schema(r, ref, defects);
            if (ref != null && !seen.add(ref)) {
                defects.add(new Defect("DUPLICATE_ROW", ref, "campaign_ref appears more than once"));
            }
            amount(r, ref, defects);
            String arm = text(r, "arm");
            if (r.path("recovered").asBoolean() && "UNKNOWN".equals(text(r, "capture_source"))) {
                defects.add(new Defect("RECOVERED_WITHOUT_ATTRIBUTION", ref, "RECOVERED with no recovery attribution"));
            } else if ("RECOVERED".equals(text(r, "state_as_of")) && r.path("recovery_attribution").isNull()) {
                // U4.3 review: also when the recovery falls outside the horizon (recovered = false)
                defects.add(new Defect("RECOVERED_WITHOUT_ATTRIBUTION", ref, "state RECOVERED with no recovery attribution"));
            }
            if (r.path("recovered").asBoolean() && r.path("capture_source").isNull()) {
                defects.add(new Defect("SCHEMA_INCONSISTENT", ref, "recovered without capture_source"));
            }
            if (horizonDays != null && r.path("capture_offset_s").isIntegralNumber()
                    && (r.path("capture_offset_s").longValue() < 0 || r.path("capture_offset_s").longValue() > horizonDays * 86_400L)) {
                defects.add(new Defect("CAPTURE_OUTSIDE_HORIZON", ref, "capture_offset_s outside 0.." + horizonDays + " days"));
            }
            assignment(r, ref, arm, defects);
            if (r.path("post_m4").asBoolean() && SnapshotRow.UNASSIGNED.equals(arm)) {
                defects.add(new Defect("ASSIGNMENT_MISSING", ref, "first eligibility after M4 start but no holdout assignment"));
            }
            if ("CONTROL".equals(arm) && r.path("contacted").asBoolean()) {
                defects.add(new Defect("CONTROL_CONTACTED", ref, "a CONTROL campaign has a provider dispatch"));
            }
            if ("CONTROL".equals(arm) && r.path("propensity_bp").isIntegralNumber() && r.path("holdout_rate_bp").isIntegralNumber()
                    && r.path("propensity_bp").intValue() != r.path("holdout_rate_bp").intValue()) {
                defects.add(new Defect("PROPENSITY_INCONSISTENT", ref, "CONTROL propensity differs from its rate"));
            }
        }
        return defects;
    }

    private static void assignment(JsonNode r, String ref, String arm, List<Defect> defects) {
        if (!"TREATMENT".equals(arm) && !"CONTROL".equals(arm)) {
            return;
        }
        JsonNode rate = r.path("holdout_rate_bp");
        JsonNode propensity = r.path("propensity_bp");
        if (!rate.isIntegralNumber() || rate.intValue() < 0 || rate.intValue() > 5000) {
            defects.add(new Defect("ASSIGNMENT_RATE_INVALID", ref, "holdout_rate_bp must be 0..5000"));
            return;
        }
        if ("TREATMENT".equals(arm) && (!propensity.isIntegralNumber() || propensity.intValue() != 10_000 - rate.intValue())) {
            defects.add(new Defect("PROPENSITY_INCONSISTENT", ref, "TREATMENT propensity must be 10000 - rate"));
        }
        if ("CONTROL".equals(arm) && rate.intValue() == 0) {
            defects.add(new Defect("ASSIGNMENT_RATE_INVALID", ref, "CONTROL at rate 0"));
        }
    }

    private static void decisions(String decisionsJsonl, Set<String> campaignRefs, List<Defect> defects) {
        List<JsonNode> rows;
        try {
            rows = SnapshotCodec.lines(decisionsJsonl);
        } catch (RuntimeException unparsable) {
            defects.add(new Defect("DECISIONS_UNPARSABLE", null, unparsable.getClass().getSimpleName()));
            return;
        }
        for (JsonNode d : rows) {
            String ref = text(d, "campaign_ref");
            if (!SnapshotRow.SCHEMA_VERSION.equals(text(d, "schema_version"))) {
                defects.add(new Defect("DECISION_SCHEMA_VERSION", ref, String.valueOf(text(d, "schema_version"))));
            }
            if (ref == null || !campaignRefs.contains(ref)) {
                defects.add(new Defect("DECISION_ORPHAN", ref, "decision for a campaign not in the snapshot"));
            }
            if (!POINTS.contains(text(d, "decision_point"))) {
                defects.add(new Defect("DECISION_BAD_POINT", ref, String.valueOf(text(d, "decision_point"))));
            }
            JsonNode p = d.path("propensity_bp");
            if (!p.isIntegralNumber() || p.intValue() < 0 || p.intValue() > 10_000) {
                defects.add(new Defect("DECISION_BAD_PROPENSITY", ref, "propensity_bp must be 0..10000"));
            }
            boolean chosenListed = false;
            for (JsonNode alt : d.path("alternatives")) {
                chosenListed |= alt.isString() && alt.stringValue().equals(text(d, "chosen"));
            }
            if (!chosenListed) {
                defects.add(new Defect("DECISION_CHOSEN_NOT_ALTERNATIVE", ref, "chosen action is not among the alternatives"));
            }
            if (!d.path("offset_s").isIntegralNumber()) {
                defects.add(new Defect("DECISION_BAD_OFFSET", ref, "offset_s must be whole seconds"));
            }
        }
    }

    private static void schema(JsonNode r, String ref, List<Defect> defects) {
        if (!r.isObject()) {
            defects.add(new Defect("SCHEMA_NOT_OBJECT", null, "line is not a JSON object"));
            return;
        }
        for (String column : SnapshotCodec.CAMPAIGN_COLUMNS) {
            if (!r.has(column)) {
                defects.add(new Defect("SCHEMA_MISSING_FIELD", ref, column));
            }
        }
        if (r.size() != SnapshotCodec.CAMPAIGN_COLUMNS.size()) {
            defects.add(new Defect("SCHEMA_EXTRA_FIELD", ref, "expected " + SnapshotCodec.CAMPAIGN_COLUMNS.size() + " fields, got " + r.size()));
        }
        if (!SnapshotRow.SCHEMA_VERSION.equals(text(r, "schema_version"))) {
            defects.add(new Defect("SCHEMA_VERSION", ref, String.valueOf(text(r, "schema_version"))));
        }
        for (String refField : List.of("merchant_ref", "campaign_ref", "invoice_ref")) {
            String v = text(r, refField);
            if (v == null || !REF.matcher(v).matches()) {
                defects.add(new Defect("SCHEMA_BAD_REF", ref, refField));
            }
        }
        String customer = text(r, "customer_ref");
        if (!r.path("customer_ref").isNull() && (customer == null || !REF.matcher(customer).matches())) {
            defects.add(new Defect("SCHEMA_BAD_REF", ref, "customer_ref"));
        }
        if (text(r, "eligible_day") == null || !DAY.matcher(text(r, "eligible_day")).matches()) {
            defects.add(new Defect("SCHEMA_BAD_DAY", ref, "eligible_day"));
        }
        if (!ARMS.contains(text(r, "arm"))) {
            defects.add(new Defect("SCHEMA_BAD_ENUM", ref, "arm"));
        }
        if (!LABELS.contains(text(r, "evidence_label"))) {
            defects.add(new Defect("SCHEMA_BAD_ENUM", ref, "evidence_label"));
        }
        if (!r.path("capture_source").isNull() && !CAPTURE_SOURCES.contains(text(r, "capture_source"))) {
            defects.add(new Defect("SCHEMA_BAD_ENUM", ref, "capture_source"));
        }
        for (String flag : List.of("post_m4", "contacted", "mature", "recovered")) {
            if (!r.path(flag).isBoolean()) {
                defects.add(new Defect("SCHEMA_BAD_TYPE", ref, flag));
            }
        }
        if (r.path("recovered").asBoolean() && !r.path("capture_offset_s").isIntegralNumber()) {
            defects.add(new Defect("SCHEMA_INCONSISTENT", ref, "recovered without capture_offset_s"));
        }
    }

    private static void amount(JsonNode r, String ref, List<Defect> defects) {
        JsonNode amount = r.path("amount_minor");
        String status = text(r, "amount_status");
        if (amount.isNull() || amount.isMissingNode()) {
            if (!"UNKNOWN".equals(status)) {
                defects.add(new Defect("AMOUNT_STATUS_INCONSISTENT", ref, "no amount but status " + status));
            }
            return;
        }
        if (!amount.isNumber()) {
            defects.add(new Defect("AMOUNT_NOT_NUMBER", ref, "amount_minor is not a number"));
        } else if (!amount.isIntegralNumber()) {
            defects.add(new Defect("AMOUNT_FRACTIONAL", ref, "amount_minor is not whole minor units"));
        } else if (!amount.canConvertToLong() || amount.longValue() < 0) {
            defects.add(new Defect("AMOUNT_NEGATIVE", ref, "amount_minor below zero or out of range"));
        } else if (amount.longValue() == 0) {
            defects.add(new Defect("AMOUNT_ZERO", ref, "a known amount must be positive"));
        }
        if (!"KNOWN".equals(status)) {
            defects.add(new Defect("AMOUNT_STATUS_INCONSISTENT", ref, "amount present but status " + status));
        }
        if (text(r, "currency") == null) {
            defects.add(new Defect("AMOUNT_WITHOUT_CURRENCY", ref, "amount without currency"));
        } else if (!CURRENCY.matcher(text(r, "currency")).matches()) {
            defects.add(new Defect("AMOUNT_BAD_CURRENCY", ref, "currency must be an ISO 4217 code"));
        }
    }

    private static String text(JsonNode r, String field) {
        JsonNode v = r.path(field);
        return v.isString() ? v.stringValue() : null;
    }
}
