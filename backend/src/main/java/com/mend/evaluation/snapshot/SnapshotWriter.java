package com.mend.evaluation.snapshot;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M4 U4.3: writes a snapshot directory (campaigns.jsonl, decisions.jsonl, campaigns.csv, manifest.json). Shared by the
 * database extractor and the simulator, so both produce the same format, checksum and validation.
 */
public final class SnapshotWriter {

    private SnapshotWriter() {
    }

    /** What was written: the checksum and the validator's defects (empty = VALID). */
    public record Written(String snapshotId, String sha256, List<SnapshotValidator.Defect> defects) {
        public boolean valid() {
            return defects.isEmpty();
        }
    }

    public static Written prepare(String snapshotId, SnapshotBuilder.Content content, int horizonDays) {
        String campaigns = SnapshotCodec.campaignsJsonl(content.campaigns());
        String decisions = SnapshotCodec.decisionsJsonl(content.decisions());
        return new Written(snapshotId, SnapshotCodec.snapshotSha256(campaigns, decisions),
                SnapshotValidator.validate(campaigns, decisions, horizonDays));
    }

    public static Written write(Path dir, String snapshotId, Instant createdAt, SnapshotParams params, SnapshotBuilder.Content content,
                                List<String> merchantRefs) {
        Written written = prepare(snapshotId, content, params.horizonDays());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("campaigns.jsonl"), SnapshotCodec.campaignsJsonl(content.campaigns()), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("decisions.jsonl"), SnapshotCodec.decisionsJsonl(content.decisions()), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("campaigns.csv"), SnapshotCodec.campaignsCsv(content.campaigns()), StandardCharsets.UTF_8);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("snapshot_id", snapshotId);
            manifest.put("schema_version", SnapshotRow.SCHEMA_VERSION);
            manifest.put("created_at", createdAt.toString());
            manifest.put("window_from", params.windowFrom().toString());
            manifest.put("window_to", params.windowTo().toString());
            manifest.put("as_of", params.asOf().toString());
            manifest.put("horizon_days", params.horizonDays());
            manifest.put("provider_mode", params.providerMode());
            manifest.put("merchant_refs", merchantRefs);
            manifest.put("row_count", content.campaigns().size());
            manifest.put("decision_count", content.decisions().size());
            manifest.put("sha256", written.sha256());
            manifest.put("sha256_rule", "sha256(\"campaigns.jsonl:\" + sha256(campaigns.jsonl) + \"\\ndecisions.jsonl:\" + sha256(decisions.jsonl) + \"\\n\")");
            manifest.put("campaigns_sha256", SnapshotCodec.sha256(SnapshotCodec.campaignsJsonl(content.campaigns())));
            manifest.put("decisions_sha256", SnapshotCodec.sha256(SnapshotCodec.decisionsJsonl(content.decisions())));
            manifest.put("evidence_label_rule", "MEASURED only for provider mode razorpay-live and non-simulated sources; else SYNTHETIC");
            manifest.put("status", written.valid() ? "VALID" : "INVALID");
            manifest.put("errors", written.defects().stream().map(d -> {
                Map<String, String> e = new LinkedHashMap<>();
                e.put("code", d.code());
                e.put("campaign_ref", d.campaignRef());
                e.put("detail", d.detail());
                return e;
            }).toList());
            Files.writeString(dir.resolve("manifest.json"),
                    JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write snapshot files to " + dir, e);
        }
        return written;
    }
}
