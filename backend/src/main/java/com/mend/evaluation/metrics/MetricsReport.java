package com.mend.evaluation.metrics;

import com.mend.evaluation.EvidenceLabel;
import com.mend.evaluation.metrics.Figure.Status;
import com.mend.evaluation.snapshot.SnapshotRow;
import com.mend.evaluation.stats.Bootstrap;
import com.mend.evaluation.stats.ChiSquare;
import com.mend.evaluation.stats.Interval;
import com.mend.evaluation.stats.Median;
import com.mend.evaluation.stats.Newcombe;
import com.mend.evaluation.stats.Wilson;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * M4 U4.4 (ADR-004 §6, metric tree): computes every figure from snapshot rows only (invariant 23). Unit: the invoice (rows
 * of one invoice collapse to its first assignment). Causal figures compare TREATMENT and CONTROL per holdout-rate stratum
 * among mature invoices (intention-to-treat); nothing is pooled across strata. Every figure has its n and label; below
 * the minimum n there is no interval ("insufficient data"); an unknown cost is TBD.
 */
public final class MetricsReport {

    public static final int MIN_N_RATE = 30;
    public static final int MIN_N_MONEY = 100;
    public static final int STABLE_N_MONEY = 300;
    public static final int MIN_RECOVERED_MEDIAN = 10;
    public static final int[] CUMULATIVE_DAYS = {1, 3, 7, 14};

    /**
     * @param costPerContactMinor configured cost of one provider contact in minor units, or null (cost TBD)
     * @param plannedTotal        pre-registered total of mature invoices for a final analysis, or null (always INTERIM)
     */
    public record Options(long seed, int resamples, Long costPerContactMinor, Long plannedTotal) {
        public Options {
            if (resamples < Bootstrap.MIN_RESAMPLES) {
                throw new IllegalArgumentException("at least " + Bootstrap.MIN_RESAMPLES + " resamples");
            }
        }
    }

    public record Report(String snapshotId, String sha256, String asOf, int horizonDays, String providerMode, String snapshotStatus,
                         EvidenceLabel label, boolean interim, Options options, List<Figure> figures, List<String> notes) {
    }

    /** One invoice as analysed: its first assignment and the outcome of that campaign. */
    record Unit(SnapshotRow row) {
        boolean control() {
            return "CONTROL".equals(row.arm());
        }
    }

    private final SnapshotFiles snapshot;
    private final Options options;
    private final EvidenceLabel label;
    private final List<Figure> figures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private MetricsReport(SnapshotFiles snapshot, Options options) {
        this.snapshot = snapshot;
        this.options = options;
        boolean synthetic = snapshot.rows().stream().anyMatch(r -> "SYNTHETIC".equals(r.evidenceLabel()))
                || "mock".equals(snapshot.providerMode()) || "simulator".equals(snapshot.providerMode());
        this.label = synthetic ? EvidenceLabel.SYNTHETIC : EvidenceLabel.MEASURED;
    }

    public static Report compute(SnapshotFiles snapshot, Options options) {
        return new MetricsReport(snapshot, options).run();
    }

    private Report run() {
        if (!snapshot.defects().isEmpty()) {
            notes.add("Snapshot is INVALID (" + snapshot.defects().size() + " defects). Figures below are reported only because "
                    + "--allow-invalid was given; they are not fit for a decision.");
        }
        if (label == EvidenceLabel.SYNTHETIC) {
            notes.add("SYNTHETIC: simulator, generated or mock-provider data. These are not revenue and support no claim about real customers.");
        } else if ("razorpay-test".equals(snapshot.providerMode())) {
            notes.add("MEASURED in Razorpay test mode: real provider evidence, but test-mode money is not revenue.");
        }
        List<SnapshotRow> rows = snapshot.rows();
        scope("overall", rows);
        rows.stream().map(SnapshotRow::merchantRef).distinct().sorted()
                .forEach(m -> scope("merchant " + m.substring(0, 12), rows.stream().filter(r -> r.merchantRef().equals(m)).toList()));
        long matureAssigned = units(rows).stream().filter(u -> u.row().mature() && rate(u.row()) > 0).count();
        boolean interim = options.plannedTotal() == null || matureAssigned < options.plannedTotal();
        notes.add(interim
                ? "INTERIM — not for decision: " + (options.plannedTotal() == null ? "no pre-registered sample size was given"
                    : "mature invoices " + matureAssigned + " < pre-registered " + options.plannedTotal())
                    + ". Repeated looks inflate false positives (no alpha spending in M4)."
                : "FINAL: the pre-registered sample (" + options.plannedTotal() + " mature invoices) is reached.");
        return new Report(snapshot.snapshotId(), snapshot.sha256(), snapshot.asOf(), snapshot.horizonDays(), snapshot.providerMode(),
                snapshot.status(), label, interim, options, List.copyOf(figures), List.copyOf(notes));
    }

    private void scope(String scope, List<SnapshotRow> rows) {
        health(scope, rows);
        List<Unit> units = units(rows);
        Map<Integer, List<Unit>> strata = units.stream().filter(u -> rate(u.row()) > 0)
                .collect(Collectors.groupingBy(u -> rate(u.row()), TreeMap::new, Collectors.toList()));
        long noHoldout = units.stream().filter(u -> rate(u.row()) == 0).count();
        add(scope, "-", "invoices without a holdout (rate 0, no CONTROL): not part of any causal figure", Long.toString(noHoldout),
                null, null, noHoldout, Status.DESCRIPTIVE, "");
        if (strata.isEmpty()) {
            add(scope, "-", "incremental recovery rate", "TBD", null, null, 0, Status.TBD, "no invoice was assigned with a holdout rate above 0");
        }
        strata.forEach((rate, stratumUnits) -> stratum(scope, "holdout " + rate + " bp", stratumUnits, rate));
    }

    private void stratum(String scope, String stratum, List<Unit> all, int rateBp) {
        List<Unit> mature = all.stream().filter(u -> u.row().mature()).toList();
        List<Unit> t = mature.stream().filter(u -> !u.control()).toList();
        List<Unit> c = mature.stream().filter(Unit::control).toList();
        add(scope, stratum, "immature invoices (eligible + horizon after as_of; excluded from outcomes)",
                Long.toString(all.size() - mature.size()), null, null, all.size() - mature.size(), Status.DESCRIPTIVE, "");

        long assignedT = all.stream().filter(u -> !u.control()).count();
        long assignedC = all.size() - assignedT;
        ChiSquare.Result srm = ChiSquare.sampleRatio(assignedC, all.size(), rateBp);
        add(scope, stratum, "sample-ratio mismatch (chi-square p; flagged below 0.001)", fmt(srm.pValue(), 6), null, null, all.size(),
                srm.mismatch() ? Status.ALERT : Status.OK, "CONTROL " + assignedC + " of " + all.size() + " at " + rateBp + " bp"
                        + (srm.mismatch() ? " — MISMATCH: assignment or data loss is suspect; do not interpret the effect" : ""));

        rates(scope, stratum, "recovery rate", t, c, u -> u.row().recovered());
        for (int day : CUMULATIVE_DAYS) {
            if (day <= snapshot.horizonDays()) {
                long limit = day * 86_400L;
                rates(scope, stratum, "cumulative recovery by day " + day, t, c,
                        u -> u.row().recovered() && u.row().captureOffsetS() != null && u.row().captureOffsetS() <= limit);
            }
        }
        money(scope, stratum, t, c);
        timeToRecovery(scope, stratum, "TREATMENT", t);
        timeToRecovery(scope, stratum, "CONTROL", c);
        contacts(scope, stratum, t, c);
        attribution(scope, stratum, "TREATMENT", t);
        attribution(scope, stratum, "CONTROL", c);
        byFailureClass(scope, stratum, t, c);
        byStrategy(scope, stratum, t);
    }

    private void rates(String scope, String stratum, String name, List<Unit> t, List<Unit> c, Predicate<Unit> success) {
        long xt = t.stream().filter(success).count();
        long xc = c.stream().filter(success).count();
        arm(scope, stratum, name + " TREATMENT", xt, t.size());
        arm(scope, stratum, name + " CONTROL", xc, c.size());
        String incremental = "incremental " + name + " (TREATMENT − CONTROL)";
        if (t.size() < MIN_N_RATE || c.size() < MIN_N_RATE) {
            add(scope, stratum, incremental, "insufficient data", null, null, t.size() + c.size(), Status.INSUFFICIENT_DATA,
                    "n TREATMENT " + t.size() + ", CONTROL " + c.size() + "; need " + MIN_N_RATE + " per arm");
            return;
        }
        Interval d = Newcombe.difference(xt, t.size(), xc, c.size());
        add(scope, stratum, incremental, pct(d.estimate()), pct(d.low()), pct(d.high()), t.size() + c.size(), Status.OK,
                "Newcombe hybrid score 95 %; causal (randomized, ITT)");
    }

    private void arm(String scope, String stratum, String name, long x, long n) {
        if (n < MIN_N_RATE) {
            add(scope, stratum, name, n == 0 ? "no data" : pct((double) x / n), null, null, n, Status.INSUFFICIENT_DATA,
                    x + " of " + n + "; no interval below n " + MIN_N_RATE);
            return;
        }
        Interval w = Wilson.interval(x, n);
        add(scope, stratum, name, pct(w.estimate()), pct(w.low()), pct(w.high()), n, Status.OK, x + " of " + n + "; Wilson 95 %");
    }

    private void money(String scope, String stratum, List<Unit> t, List<Unit> c) {
        long[] mt = recoveredMinor(t);
        long[] mc = recoveredMinor(c);
        long unknown = t.size() + c.size() - mt.length - mc.length;
        add(scope, stratum, "invoices with unknown or non-INR amount (excluded from money figures)", Long.toString(unknown), null, null,
                unknown, Status.DESCRIPTIVE, "");
        for (String arm : List.of("TREATMENT", "CONTROL")) {
            long[] values = arm.equals("TREATMENT") ? mt : mc;
            add(scope, stratum, "recovered amount per eligible invoice " + arm + " (INR)", values.length == 0 ? "no data"
                    : rupees(Bootstrap.mean(values)), null, null, values.length, Status.DESCRIPTIVE, "sum " + rupees(sum(values)) + " INR");
        }
        String name = "incremental recovered amount per eligible invoice (INR, TREATMENT − CONTROL)";
        if (mt.length < MIN_N_MONEY || mc.length < MIN_N_MONEY) {
            add(scope, stratum, name, "insufficient data", null, null, mt.length + mc.length, Status.INSUFFICIENT_DATA,
                    "n " + mt.length + " / " + mc.length + "; need " + MIN_N_MONEY + " per arm");
            cost(scope, stratum, t, null);
            return;
        }
        Bootstrap.BigInterval d = Bootstrap.meanDifference(mt, mc, options.resamples(), options.seed());
        boolean stable = mt.length >= STABLE_N_MONEY && mc.length >= STABLE_N_MONEY;
        add(scope, stratum, name, rupees(d.estimate()), rupees(d.low()), rupees(d.high()), mt.length + mc.length,
                stable ? Status.OK : Status.UNSTABLE, "seeded percentile bootstrap (seed " + options.seed() + ", " + options.resamples()
                        + " resamples, stratified by arm); exact means rounded HALF_EVEN to the paisa; gross of refunds"
                        + (stable ? "" : "; UNSTABLE below " + STABLE_N_MONEY + " per arm"));
        cost(scope, stratum, t, d.estimate());
    }

    private void cost(String scope, String stratum, List<Unit> t, BigDecimal incrementalMinorPerInvoice) {
        String name = "cost per incremental recovered rupee";
        if (options.costPerContactMinor() == null) {
            add(scope, stratum, name, "TBD", null, null, t.size(), Status.TBD, "no cost model configured");
            add(scope, stratum, "cost per observed recovered rupee (non-causal)", "TBD", null, null, t.size(), Status.TBD, "no cost model configured");
            return;
        }
        long contacts = t.stream().mapToLong(u -> u.row().contactCount()).sum();
        BigDecimal costPerInvoice = t.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(contacts * options.costPerContactMinor())
                .divide(BigDecimal.valueOf(t.size()), 6, RoundingMode.HALF_EVEN);
        if (incrementalMinorPerInvoice == null || incrementalMinorPerInvoice.signum() <= 0) {
            add(scope, stratum, name, "TBD", null, null, t.size(), Status.TBD,
                    incrementalMinorPerInvoice == null ? "incremental money not measured (insufficient data)" : "no positive incremental money");
        } else {
            add(scope, stratum, name, costPerInvoice.divide(incrementalMinorPerInvoice, 4, RoundingMode.HALF_EVEN).toPlainString(), null, null,
                    t.size(), Status.OK, "configured cost " + options.costPerContactMinor() + " minor units per contact; " + contacts + " contacts");
        }
        long recovered = sum(recoveredMinor(t));
        add(scope, stratum, "cost per observed recovered rupee (non-causal)", recovered == 0 ? "TBD"
                        : BigDecimal.valueOf(contacts * options.costPerContactMinor()).divide(BigDecimal.valueOf(recovered), 4, RoundingMode.HALF_EVEN).toPlainString(),
                null, null, t.size(), recovered == 0 ? Status.TBD : Status.DESCRIPTIVE,
                "includes recoveries MEND did not cause (late captures); not a decision metric");
    }

    private void timeToRecovery(String scope, String stratum, String arm, List<Unit> units) {
        long[] seconds = units.stream().filter(u -> u.row().recovered() && u.row().captureOffsetS() != null)
                .mapToLong(u -> u.row().captureOffsetS()).toArray();
        String name = "median time to recovery among recovered, " + arm + " (hours; DESCRIPTIVE, not causal)";
        if (seconds.length < MIN_RECOVERED_MEDIAN) {
            add(scope, stratum, name, seconds.length == 0 ? "no data" : hours(Median.of(seconds)), null, null, seconds.length,
                    Status.INSUFFICIENT_DATA, "no interval below " + MIN_RECOVERED_MEDIAN + " recoveries");
            return;
        }
        Bootstrap.BigInterval m = Bootstrap.median(seconds, options.resamples(), options.seed());
        add(scope, stratum, name, hours(m.estimate()), hours(m.low()), hours(m.high()), seconds.length, Status.DESCRIPTIVE,
                "conditions on the outcome, so arms are not comparable causally; bootstrap 95 % (coarse at small n)");
    }

    private void contacts(String scope, String stratum, List<Unit> t, List<Unit> c) {
        long ct = t.stream().filter(u -> u.row().contacted()).count();
        long cc = c.stream().filter(u -> u.row().contacted()).count();
        add(scope, stratum, "contact rate TREATMENT", t.isEmpty() ? "no data" : pct((double) ct / t.size()), null, null, t.size(),
                Status.DESCRIPTIVE, ct + " contacted");
        add(scope, stratum, "contact rate CONTROL (must be 0)", c.isEmpty() ? "no data" : pct((double) cc / c.size()), null, null, c.size(),
                cc == 0 ? Status.DESCRIPTIVE : Status.ALERT, cc == 0 ? "" : cc + " CONTROL invoices contacted: contamination");
    }

    private void attribution(String scope, String stratum, String arm, List<Unit> units) {
        Map<String, List<Unit>> bySource = units.stream().filter(u -> u.row().recovered())
                .collect(Collectors.groupingBy(u -> u.row().captureSource(), TreeMap::new, Collectors.toList()));
        for (String source : List.of("MEND", "ORIGINAL", "SYNTHETIC", "UNKNOWN")) {
            List<Unit> list = bySource.getOrDefault(source, List.of());
            add(scope, stratum, "recoveries attributed " + source + ", " + arm, Long.toString(list.size()), null, null, units.size(),
                    Status.DESCRIPTIVE, "amount " + rupees(sum(recoveredMinor(list))) + " INR (known amounts only)");
        }
    }

    private void byFailureClass(String scope, String stratum, List<Unit> t, List<Unit> c) {
        TreeSet<String> classes = new TreeSet<>();
        t.forEach(u -> classes.add(cls(u)));
        c.forEach(u -> classes.add(cls(u)));
        for (String cls : classes) {
            rates(scope, stratum + " / class " + cls, "recovery rate",
                    t.stream().filter(u -> cls(u).equals(cls)).toList(), c.stream().filter(u -> cls(u).equals(cls)).toList(),
                    u -> u.row().recovered());
        }
    }

    private void byStrategy(String scope, String stratum, List<Unit> t) {
        Map<String, List<Unit>> byStrategy = t.stream()
                .collect(Collectors.groupingBy(u -> u.row().strategy() == null ? "none" : u.row().strategy(), TreeMap::new, Collectors.toList()));
        byStrategy.forEach((strategy, list) -> {
            long x = list.stream().filter(u -> u.row().recovered()).count();
            add(scope, stratum + " / strategy " + strategy, "recovery rate TREATMENT (DESCRIPTIVE: strategy follows the class, not randomized)",
                    pct((double) x / list.size()), null, null, list.size(), Status.DESCRIPTIVE, "channel " + channel(strategy));
        });
    }

    private static String channel(String strategy) {
        return switch (strategy) {
            case "CUSTOMER_ACTION_REQUIRED" -> "payment link";
            case "RETRY_IMMEDIATELY", "RETRY_LATER" -> "merchant retry (debit not wired: no provider action in M4)";
            case "MANUAL_REVIEW" -> "human review";
            default -> "none";
        };
    }

    private void health(String scope, List<SnapshotRow> rows) {
        long unassigned = rows.stream().filter(r -> SnapshotRow.UNASSIGNED.equals(r.arm())).count();
        long postM4Missing = rows.stream().filter(r -> r.postM4() && SnapshotRow.UNASSIGNED.equals(r.arm())).count();
        long controlContacted = rows.stream().filter(r -> "CONTROL".equals(r.arm()) && r.contacted()).count();
        long unclassified = rows.stream().filter(r -> "UNCLASSIFIED".equals(r.failureClass())).count();
        long legacy = rows.stream().mapToLong(SnapshotRow::legacyDecisionBlocks).sum();
        long amountUnknown = rows.stream().filter(r -> r.amountMinor() == null).count();
        String h = "system health";
        add(scope, h, "campaign rows", Long.toString(rows.size()), null, null, rows.size(), Status.DESCRIPTIVE, "");
        add(scope, h, "UNASSIGNED (pre-M4) campaigns, outside the experiment", Long.toString(unassigned), null, null, rows.size(), Status.DESCRIPTIVE, "");
        add(scope, h, "post-M4 campaigns without an assignment (must be 0)", Long.toString(postM4Missing), null, null, rows.size(),
                postM4Missing == 0 ? Status.DESCRIPTIVE : Status.ALERT, "");
        add(scope, h, "CONTROL campaigns contacted (must be 0)", Long.toString(controlContacted), null, null, rows.size(),
                controlContacted == 0 ? Status.DESCRIPTIVE : Status.ALERT, "");
        add(scope, h, "UNCLASSIFIED volume", Long.toString(unclassified), null, null, rows.size(), Status.DESCRIPTIVE, "");
        add(scope, h, "LEGACY_DECISION dispatch blocks", Long.toString(legacy), null, null, rows.size(), Status.DESCRIPTIVE, "");
        add(scope, h, "amount unknown", Long.toString(amountUnknown), null, null, rows.size(), Status.DESCRIPTIVE, "");
        Map<String, Set<String>> armsByCustomer = new HashMap<>();
        rows.stream().filter(r -> r.customerRef() != null && !SnapshotRow.UNASSIGNED.equals(r.arm()))
                .forEach(r -> armsByCustomer.computeIfAbsent(r.customerRef(), k -> new HashSet<>()).add(r.arm()));
        if (armsByCustomer.isEmpty()) {
            add(scope, h, "customers with invoices in both arms (interference)", "TBD", null, null, 0, Status.TBD,
                    "no customer key in the snapshot");
        } else {
            long both = armsByCustomer.values().stream().filter(s -> s.size() > 1).count();
            add(scope, h, "customers with invoices in both arms (interference)", Long.toString(both), null, null, armsByCustomer.size(),
                    Status.DESCRIPTIVE, "");
        }
    }

    /** One unit per invoice: the HASH row (first assignment) or, failing that, the earliest row. */
    static List<Unit> units(List<SnapshotRow> rows) {
        Map<String, SnapshotRow> first = new TreeMap<>();
        rows.stream().filter(r -> !SnapshotRow.UNASSIGNED.equals(r.arm()))
                .sorted(Comparator.comparing(SnapshotRow::eligibleDay)
                        .thenComparing(r -> "HASH".equals(r.assignmentSource()) && rate(r) > 0 ? 0 : 1)
                        .thenComparing(SnapshotRow::campaignRef))
                .forEach(r -> first.putIfAbsent(r.merchantRef() + "/" + r.invoiceRef(), r));
        return first.values().stream().map(Unit::new).toList();
    }

    private static int rate(SnapshotRow r) {
        return r.holdoutRateBp() == null ? 0 : r.holdoutRateBp();
    }

    private static String cls(Unit u) {
        return u.row().failureClass() == null ? "none" : u.row().failureClass();
    }

    private static long[] recoveredMinor(List<Unit> units) {
        return units.stream().filter(u -> u.row().amountMinor() != null && "INR".equals(u.row().currency()))
                .mapToLong(u -> u.row().recovered() ? u.row().amountMinor() : 0L).toArray();
    }

    private static long sum(long[] values) {
        long s = 0;
        for (long v : values) {
            s = Math.addExact(s, v);
        }
        return s;
    }

    private void add(String scope, String stratum, String name, String value, String low, String high, long n, Status status, String note) {
        figures.add(new Figure(scope, stratum, name, value, low, high, n, status == Status.TBD ? EvidenceLabel.TBD : label, status, note));
    }

    static String pct(double v) {
        return BigDecimal.valueOf(v * 100).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + " %";
    }

    static String fmt(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Minor units (paise) to rupees, exact, rounded HALF_EVEN to the paisa. */
    static String rupees(BigDecimal minor) {
        return minor.setScale(0, RoundingMode.HALF_EVEN).movePointLeft(2).toPlainString();
    }

    static String rupees(long minor) {
        return BigDecimal.valueOf(minor).movePointLeft(2).toPlainString();
    }

    static String hours(BigDecimal seconds) {
        return seconds.divide(BigDecimal.valueOf(3600), 1, RoundingMode.HALF_EVEN).toPlainString();
    }
}
