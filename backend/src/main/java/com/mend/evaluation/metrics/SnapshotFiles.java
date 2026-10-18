package com.mend.evaluation.metrics;

import com.mend.evaluation.snapshot.SnapshotCodec;
import com.mend.evaluation.snapshot.SnapshotRow;
import com.mend.evaluation.snapshot.SnapshotValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * M4 U4.4 (invariant 23): metrics read snapshot files only. The files are checked against the manifest's sha256 and
 * re-validated; a tampered snapshot is refused, an INVALID one is refused unless explicitly allowed (and then labelled).
 */
public record SnapshotFiles(String snapshotId, String sha256, String asOf, int horizonDays, String providerMode, String status,
                            List<SnapshotRow> rows, List<SnapshotValidator.Defect> defects) {

    public static SnapshotFiles read(Path dir, boolean allowInvalid) {
        try {
            JsonNode manifest = JsonMapper.builder().build().readTree(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
            String campaigns = Files.readString(dir.resolve("campaigns.jsonl"), StandardCharsets.UTF_8);
            String decisions = Files.readString(dir.resolve("decisions.jsonl"), StandardCharsets.UTF_8);
            String sha = SnapshotCodec.snapshotSha256(campaigns, decisions);
            if (!sha.equals(manifest.path("sha256").stringValue())) {
                throw new IllegalStateException("snapshot files do not match the manifest sha256 (refused)");
            }
            List<SnapshotValidator.Defect> defects = SnapshotValidator.validate(campaigns, decisions, manifest.path("horizon_days").intValue());
            String status = defects.isEmpty() ? "VALID" : "INVALID";
            if (!status.equals(manifest.path("status").stringValue())) {
                throw new IllegalStateException("manifest status " + manifest.path("status").stringValue() + " but validation says " + status);
            }
            if (!defects.isEmpty() && !allowInvalid) {
                throw new IllegalStateException("snapshot is INVALID (" + defects.size() + " defects); refused without --allow-invalid");
            }
            List<SnapshotRow> rows;
            try {
                rows = SnapshotCodec.parseCampaigns(campaigns);
            } catch (RuntimeException unparsable) {
                if (defects.isEmpty()) {
                    throw unparsable;
                }
                rows = List.of(); // an allowed INVALID snapshot that cannot even be parsed: report its defects only
            }
            return new SnapshotFiles(manifest.path("snapshot_id").stringValue(), sha, manifest.path("as_of").stringValue(),
                    manifest.path("horizon_days").intValue(), manifest.path("provider_mode").stringValue(), status, rows, defects);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
