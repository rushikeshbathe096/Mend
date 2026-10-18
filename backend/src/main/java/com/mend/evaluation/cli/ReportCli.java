package com.mend.evaluation.cli;

import com.mend.evaluation.metrics.MetricsReport;
import com.mend.evaluation.metrics.ReportWriter;
import com.mend.evaluation.metrics.SnapshotFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * M4 U4.4: {@code scripts/eval/report.sh} entry point. Reads one snapshot directory (files only, never a database) and
 * writes {@code <out>/<snapshot-id>.md} and {@code .json}.
 * <pre>
 *   --snapshot DIR  --out DIR  [--seed N] [--resamples N] [--cost-per-contact-minor N] [--planned-total N] [--allow-invalid]
 *   [--title TEXT]
 * </pre>
 */
public final class ReportCli {

    public static final long DEFAULT_SEED = 20261018L;

    private ReportCli() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> a = parse(args);
        if (!a.containsKey("snapshot") || !a.containsKey("out")) {
            System.err.println("usage: --snapshot DIR --out DIR [--seed N] [--resamples N] [--cost-per-contact-minor N] "
                    + "[--planned-total N] [--allow-invalid] [--title TEXT]");
            System.exit(2);
        }
        Path written = run(a);
        System.out.println("report: " + written);
    }

    public static Path run(Map<String, String> a) throws IOException {
        SnapshotFiles snapshot = SnapshotFiles.read(Path.of(a.get("snapshot")), a.containsKey("allow-invalid"));
        MetricsReport.Options options = new MetricsReport.Options(
                Long.parseLong(a.getOrDefault("seed", Long.toString(DEFAULT_SEED))),
                Integer.parseInt(a.getOrDefault("resamples", "2000")),
                a.containsKey("cost-per-contact-minor") ? Long.valueOf(a.get("cost-per-contact-minor")) : null,
                a.containsKey("planned-total") ? Long.valueOf(a.get("planned-total")) : null);
        MetricsReport.Report report = MetricsReport.compute(snapshot, options);
        Path out = Path.of(a.get("out"));
        Files.createDirectories(out);
        String title = a.getOrDefault("title", "M4 evaluation report — snapshot " + snapshot.snapshotId());
        Path md = out.resolve(snapshot.snapshotId() + ".md");
        Files.writeString(md, ReportWriter.markdown(report, title), StandardCharsets.UTF_8);
        Files.writeString(out.resolve(snapshot.snapshotId() + ".json"), ReportWriter.json(report), StandardCharsets.UTF_8);
        return md;
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + args[i]);
            }
            String key = args[i].substring(2);
            if (key.equals("allow-invalid")) {
                out.put(key, "true");
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for --" + key);
                }
                out.put(key, args[++i]);
            }
        }
        return out;
    }
}
