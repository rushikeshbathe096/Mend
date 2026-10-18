package com.mend.evaluation.snapshot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M4 U4.3: canonical snapshot files. One JSON object per line, keys in a fixed order, LF endings, UTF-8; CSV with the same
 * columns. The checksum covers the file contents only (never the creation time), so the same facts give the same sha256.
 */
public final class SnapshotCodec {

    public static final List<String> CAMPAIGN_COLUMNS = List.of(
            "schema_version", "merchant_ref", "campaign_ref", "invoice_ref", "customer_ref", "eligible_day", "arm",
            "holdout_rate_bp", "propensity_bp", "assignment_source", "salt_version", "hash_version", "post_m4", "failure_class",
            "taxonomy_version", "strategy", "compliance", "compliance_reason", "contacted", "contact_count",
            "first_contact_offset_s", "mature", "recovered", "capture_offset_s", "capture_source", "recovery_attribution",
            "amount_minor", "currency", "amount_status", "state_as_of", "legacy_decision_blocks", "evidence_label");

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private SnapshotCodec() {
    }

    public static Map<String, Object> toMap(SnapshotRow r) {
        Object[] values = {r.schemaVersion(), r.merchantRef(), r.campaignRef(), r.invoiceRef(), r.customerRef(), r.eligibleDay(),
                r.arm(), r.holdoutRateBp(), r.propensityBp(), r.assignmentSource(), r.saltVersion(), r.hashVersion(), r.postM4(),
                r.failureClass(), r.taxonomyVersion(), r.strategy(), r.compliance(), r.complianceReason(), r.contacted(),
                r.contactCount(), r.firstContactOffsetS(), r.mature(), r.recovered(), r.captureOffsetS(), r.captureSource(),
                r.recoveryAttribution(), r.amountMinor(), r.currency(), r.amountStatus(), r.stateAsOf(), r.legacyDecisionBlocks(),
                r.evidenceLabel()};
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            map.put(CAMPAIGN_COLUMNS.get(i), values[i]);
        }
        return map;
    }

    public static String campaignsJsonl(List<SnapshotRow> rows) {
        StringBuilder out = new StringBuilder();
        for (SnapshotRow r : rows) {
            out.append(JSON.writeValueAsString(toMap(r))).append('\n');
        }
        return out.toString();
    }

    public static String decisionsJsonl(List<DecisionRow> rows) {
        StringBuilder out = new StringBuilder();
        for (DecisionRow d : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("schema_version", d.schemaVersion());
            map.put("merchant_ref", d.merchantRef());
            map.put("campaign_ref", d.campaignRef());
            map.put("intent_ref", d.intentRef());
            map.put("decision_point", d.decisionPoint());
            map.put("chosen", d.chosen());
            map.put("reason_code", d.reasonCode());
            map.put("alternatives", d.alternatives());
            map.put("propensity_bp", d.propensityBp());
            map.put("taxonomy_version", d.taxonomyVersion());
            map.put("strategy_version", d.strategyVersion());
            map.put("compliance_version", d.complianceVersion());
            map.put("assignment_version", d.assignmentVersion());
            map.put("offset_s", d.offsetS());
            out.append(JSON.writeValueAsString(map)).append('\n');
        }
        return out.toString();
    }

    public static String campaignsCsv(List<SnapshotRow> rows) {
        StringBuilder out = new StringBuilder(String.join(",", CAMPAIGN_COLUMNS)).append('\n');
        for (SnapshotRow r : rows) {
            List<String> cells = new ArrayList<>();
            for (Object v : toMap(r).values()) {
                cells.add(csv(v));
            }
            out.append(String.join(",", cells)).append('\n');
        }
        return out.toString();
    }

    private static String csv(Object v) {
        if (v == null) {
            return "";
        }
        String s = v.toString();
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** The lines of a JSONL file as JSON trees (blank lines are an error, not skipped). */
    public static List<JsonNode> lines(String jsonl) {
        List<JsonNode> nodes = new ArrayList<>();
        if (jsonl.isEmpty()) {
            return nodes;
        }
        String body = jsonl.endsWith("\n") ? jsonl.substring(0, jsonl.length() - 1) : jsonl;
        for (String line : body.split("\n", -1)) {
            nodes.add(JSON.readTree(line));
        }
        return nodes;
    }

    /** Parses campaign rows; assumes a file that passed {@link SnapshotValidator}. */
    public static List<SnapshotRow> parseCampaigns(String jsonl) {
        List<SnapshotRow> rows = new ArrayList<>();
        for (JsonNode n : lines(jsonl)) {
            rows.add(new SnapshotRow(str(n, "schema_version"), str(n, "merchant_ref"), str(n, "campaign_ref"), str(n, "invoice_ref"),
                    str(n, "customer_ref"), str(n, "eligible_day"), str(n, "arm"), integer(n, "holdout_rate_bp"),
                    integer(n, "propensity_bp"), str(n, "assignment_source"), integer(n, "salt_version"), str(n, "hash_version"),
                    n.path("post_m4").asBoolean(), str(n, "failure_class"), str(n, "taxonomy_version"), str(n, "strategy"),
                    str(n, "compliance"), str(n, "compliance_reason"), n.path("contacted").asBoolean(), n.path("contact_count").asInt(),
                    lng(n, "first_contact_offset_s"), n.path("mature").asBoolean(), n.path("recovered").asBoolean(),
                    lng(n, "capture_offset_s"), str(n, "capture_source"), str(n, "recovery_attribution"), lng(n, "amount_minor"),
                    str(n, "currency"), str(n, "amount_status"), str(n, "state_as_of"), n.path("legacy_decision_blocks").asInt(),
                    str(n, "evidence_label")));
        }
        return rows;
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isString() ? v.stringValue() : null;
    }

    private static Integer integer(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isIntegralNumber() ? v.intValue() : null;
    }

    private static Long lng(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isIntegralNumber() ? v.longValue() : null;
    }

    public static String sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
