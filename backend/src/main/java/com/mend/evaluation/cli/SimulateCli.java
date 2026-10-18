package com.mend.evaluation.cli;

import com.mend.evaluation.sim.*;
import com.mend.evaluation.snapshot.SnapshotWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M4 U4.5 / U4.6: {@code scripts/eval/simulate.sh} entry point (everything SYNTHETIC, in memory, no database).
 * <pre>
 *   --out DIR [--snapshots DIR] [--seed N] [--size N] [--holdout-bp N] [--horizon-days N] [--resamples N]
 * </pre>
 * Writes: a snapshot directory {@code <snapshots>/sim-<seed>-<size>/} of the production policy (B) with the holdout,
 * the metrics report {@code <out>/sim-<seed>-<size>.md|json}, and {@code <out>/sim-<seed>-<size>-policies.md}
 * (decision B vs A; retry timing with hypothetical wired retries).
 */
public final class SimulateCli {

    public static final Instant WINDOW_START = Instant.parse("2026-01-01T00:00:00Z");
    public static final Duration WINDOW = Duration.ofDays(28);

    private SimulateCli() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> a = ReportCli.parse(args);
        if (!a.containsKey("out")) {
            System.err.println("usage: --out DIR [--snapshots DIR] [--seed N] [--size N] [--holdout-bp N] [--horizon-days N] [--resamples N]");
            System.exit(2);
        }
        System.out.println("written: " + run(a));
    }

    public static List<Path> run(Map<String, String> a) throws IOException {
        long seed = Long.parseLong(a.getOrDefault("seed", Long.toString(ReportCli.DEFAULT_SEED)));
        int size = Integer.parseInt(a.getOrDefault("size", "10000"));
        int holdout = Integer.parseInt(a.getOrDefault("holdout-bp", "2000"));
        int horizon = Integer.parseInt(a.getOrDefault("horizon-days", "14"));
        int resamples = Integer.parseInt(a.getOrDefault("resamples", "2000"));
        Path out = Path.of(a.get("out"));
        Path snapshots = Path.of(a.getOrDefault("snapshots", out.resolve("snapshots").toString()));
        String id = "sim-" + seed + "-" + size;

        UUID merchant = UUID.nameUUIDFromBytes(("sim-merchant:" + seed).getBytes(StandardCharsets.UTF_8));
        List<ReplayEvent> events = SyntheticGenerator.generate(seed, size, merchant, WINDOW_START, WINDOW);
        ReplaySimulator simulator = new ReplaySimulator();
        OutcomeModel model = OutcomeModel.defaults();

        ReplaySimulator.Result experiment = simulator.run(events,
                new ReplaySimulator.Config(seed, holdout, horizon, PolicyVariant.B_CURRENT, model, WINDOW_START, WINDOW));
        Path snapshotDir = snapshots.resolve(id);
        SnapshotWriter.Written written = SnapshotWriter.write(snapshotDir, id, WINDOW_START.plus(WINDOW).plus(Duration.ofDays(horizon)),
                experiment.params(), experiment.content(), List.of(experiment.ids().merchantRef(merchant)));
        if (!written.valid()) {
            throw new IllegalStateException("simulated snapshot is INVALID: " + written.defects());
        }
        Path report = ReportCli.run(Map.of("snapshot", snapshotDir.toString(), "out", out.toString(), "seed", Long.toString(seed),
                "resamples", Integer.toString(resamples), "title", "M4 synthetic experiment — " + id + " (SYNTHETIC)"));

        StringBuilder md = new StringBuilder("# Policy comparison — " + id + " (SYNTHETIC)\n\n"
                + "Same " + size + " synthetic failures (seed " + seed + "), same outcome draws per failure (common random numbers), "
                + "production engines in both runs, no holdout (every eligible failure acts). Outcome model: `OutcomeModel.defaults()` "
                + "(assumptions, documented in the class). None of this is revenue.\n\n");
        ReplaySimulator.Result a0 = simulator.run(events, new ReplaySimulator.Config(seed, 0, horizon, PolicyVariant.A_UNCLASSIFIED_INELIGIBLE, model, WINDOW_START, WINDOW));
        ReplaySimulator.Result b0 = simulator.run(events, new ReplaySimulator.Config(seed, 0, horizon, PolicyVariant.B_CURRENT, model, WINDOW_START, WINDOW));
        md.append(PolicyComparison.markdown("Owner decision B (UNCLASSIFIED → payment link) vs A (UNCLASSIFIED ineligible)",
                PolicyComparison.compare("A: UNCLASSIFIED ineligible", a0, "B: UNCLASSIFIED link (current)", b0, resamples, seed)));
        OutcomeModel fast = model.withRetries(true, 0.15, Duration.ofMinutes(15));
        OutcomeModel slow = model.withRetries(true, 0.15, Duration.ofHours(48));
        ReplaySimulator.Result rf = simulator.run(events, new ReplaySimulator.Config(seed, 0, horizon, PolicyVariant.B_CURRENT, fast, WINDOW_START, WINDOW));
        ReplaySimulator.Result rs = simulator.run(events, new ReplaySimulator.Config(seed, 0, horizon, PolicyVariant.B_CURRENT, slow, WINDOW_START, WINDOW));
        md.append("\n").append(PolicyComparison.markdown("Retry timing, HYPOTHETICAL wired retries (debits are not wired in MEND): RETRY_LATER after 48 h vs 15 min",
                PolicyComparison.compare("retry after 48 h", rs, "retry after 15 min", rf, resamples, seed)));
        md.append("\nThe retry comparison is an artefact of the assumed model (success halves after 24 h); it says nothing about real "
                + "retries, which MEND does not perform (RETRY_PAYMENT is NotSupported in Razorpay mode).\n");
        Path policies = out.resolve(id + "-policies.md");
        Files.writeString(policies, md.toString(), StandardCharsets.UTF_8);
        return List.of(snapshotDir, report, policies);
    }
}
