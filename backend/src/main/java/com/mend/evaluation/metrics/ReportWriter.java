package com.mend.evaluation.metrics;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M4 U4.4: the report as Markdown (for docs/m4/results) and JSON (same figures, machine-readable). */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static String markdown(MetricsReport.Report r, String title) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("**Label: ").append(r.label()).append("**").append(r.interim() ? " · **INTERIM — not for decision**" : " · FINAL").append("\n\n");
        md.append("| snapshot | sha256 | as_of | horizon | provider mode | status | bootstrap |\n|---|---|---|---|---|---|---|\n");
        md.append("| `").append(r.snapshotId()).append("` | `").append(r.sha256(), 0, 16).append("…` | ").append(r.asOf()).append(" | ")
                .append(r.horizonDays()).append(" d | ").append(r.providerMode()).append(" | ").append(r.snapshotStatus()).append(" | seed ")
                .append(r.options().seed()).append(", ").append(r.options().resamples()).append(" resamples |\n\n");
        for (String note : r.notes()) {
            md.append("> ").append(note).append("\n");
        }
        md.append("\nEvery figure: value, 95 % interval where computed, n, label (MEASURED / SYNTHETIC / TBD) and status. "
                + "\"insufficient data\" = n below the minimum (rates 30, money 100 per arm, median 10 recoveries); no interval is "
                + "reported then. Causal figures are the incremental ones (randomized TREATMENT − CONTROL, ITT, per holdout-rate stratum).\n");
        String scope = null;
        String stratum = null;
        for (Figure f : r.figures()) {
            if (!f.scope().equals(scope)) {
                scope = f.scope();
                stratum = null;
                md.append("\n## ").append(scope).append("\n");
            }
            if (!f.stratum().equals(stratum)) {
                stratum = f.stratum();
                md.append("\n### ").append(stratum).append("\n\n| figure | value | 95 % interval | n | label | status | note |\n|---|---|---|---|---|---|---|\n");
            }
            md.append("| ").append(f.name()).append(" | ").append(f.value()).append(" | ")
                    .append(f.low() == null ? "—" : f.low() + " … " + f.high()).append(" | ").append(f.n()).append(" | ")
                    .append(f.label()).append(" | ").append(f.status()).append(" | ").append(f.note().replace("|", "/")).append(" |\n");
        }
        return md.toString();
    }

    public static String json(MetricsReport.Report r) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("snapshot_id", r.snapshotId());
        root.put("sha256", r.sha256());
        root.put("as_of", r.asOf());
        root.put("horizon_days", r.horizonDays());
        root.put("provider_mode", r.providerMode());
        root.put("snapshot_status", r.snapshotStatus());
        root.put("label", r.label().name());
        root.put("interim", r.interim());
        root.put("seed", r.options().seed());
        root.put("resamples", r.options().resamples());
        root.put("cost_per_contact_minor", r.options().costPerContactMinor());
        root.put("planned_total", r.options().plannedTotal());
        root.put("notes", r.notes());
        List<Map<String, Object>> figures = new ArrayList<>();
        for (Figure f : r.figures()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scope", f.scope());
            m.put("stratum", f.stratum());
            m.put("name", f.name());
            m.put("value", f.value());
            m.put("low", f.low());
            m.put("high", f.high());
            m.put("n", f.n());
            m.put("label", f.label().name());
            m.put("status", f.status().name());
            m.put("note", f.note());
            figures.add(m);
        }
        root.put("figures", figures);
        return JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }
}
