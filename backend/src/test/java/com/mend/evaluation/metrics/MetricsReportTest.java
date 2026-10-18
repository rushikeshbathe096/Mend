package com.mend.evaluation.metrics;

import com.mend.evaluation.EvidenceLabel;
import com.mend.evaluation.cli.ReportCli;
import com.mend.evaluation.snapshot.*;
import com.mend.evaluation.snapshot.CampaignFacts.Capture;
import com.mend.evaluation.snapshot.CampaignFacts.CaptureSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** M4 U4.4: the report against hand-computed values, labels, insufficient data, TBD cost and file integrity. */
class MetricsReportTest {

    private static final UUID MERCHANT = UUID.fromString("00000000-0000-0000-0000-00000000m4m4".replace("m", "a"));
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-10-01T00:00:00Z");

    @TempDir
    Path tmp;

    /**
     * {@code treatment}/{@code control} invoices, of which the first {@code recT}/{@code recC} are recovered (MEND for
     * TREATMENT, ORIGINAL for CONTROL) after {@code hours}; all amounts 1 000.00 INR (100 000 paise), at rate 0.5.
     */
    private Path snapshot(String mode, int treatment, int recT, int control, int recC, int hours) {
        List<CampaignFacts> facts = new ArrayList<>();
        for (int i = 0; i < treatment + control; i++) {
            boolean isControl = i >= treatment;
            int k = isControl ? i - treatment : i;
            boolean recovered = k < (isControl ? recC : recT);
            Instant eligible = T0.plusSeconds(i);
            List<Capture> captures = recovered ? List.of(new Capture(eligible.plus(Duration.ofHours(hours)),
                    isControl ? CaptureSource.ORIGINAL : CaptureSource.MEND)) : List.of();
            facts.add(new CampaignFacts(MERCHANT, UUID.nameUUIDFromBytes(("c" + i).getBytes()), "inv:inv_" + i, null, eligible,
                    new CampaignFacts.Assignment(isControl ? "CONTROL" : "TREATMENT", 5000, 5000, "HASH", 1, "h"),
                    i % 2 == 0 ? "INSUFFICIENT_FUNDS" : "CARD_DECLINED_UNMAPPED", "t", isControl ? null : "CUSTOMER_ACTION_REQUIRED",
                    isControl ? null : "ALLOWED", null, isControl ? List.of() : List.of(eligible.plusSeconds(60)), captures,
                    recovered ? (isControl ? "ORIGINAL_PAYMENT_LATE_CAPTURE" : "MEND") : null, recovered ? "RECOVERED" : "ELIGIBLE",
                    100_000L, "INR", 0, T0, false));
        }
        SnapshotParams params = new SnapshotParams(T0, T0.plus(Duration.ofDays(1)), AS_OF, 14, null, mode, List.of());
        byte[] salt = new byte[32];
        SnapshotBuilder.Content content = SnapshotBuilder.build(facts, List.of(), new Pseudonyms(Map.of(MERCHANT, salt)), params);
        Path dir = tmp.resolve(mode + "-" + treatment + "-" + control);
        SnapshotWriter.write(dir, "snap-" + treatment + "-" + control, Instant.EPOCH, params, content, List.of());
        return dir;
    }

    private static MetricsReport.Report report(Path dir, Long cost, Long planned) {
        return MetricsReport.compute(SnapshotFiles.read(dir, false), new MetricsReport.Options(7L, 2000, cost, planned));
    }

    private static Figure figure(MetricsReport.Report r, String stratum, String name) {
        return r.figures().stream().filter(f -> f.scope().equals("overall") && f.stratum().equals(stratum) && f.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no figure " + stratum + " / " + name));
    }

    @Test
    @DisplayName("40 vs 40, 12 vs 4 recovered: rates 30 % / 10 % (Wilson), incremental 20.00 % (Newcombe 2.32 … 36.57 %)")
    void ratesAgainstHandComputedValues() {
        MetricsReport.Report r = report(snapshot("simulator", 40, 12, 40, 4, 30), null, null);
        String s = "holdout 5000 bp";
        Figure t = figure(r, s, "recovery rate TREATMENT");
        assertEquals("30.00 %", t.value());
        assertEquals("18.07 %", t.low());
        assertEquals("45.43 %", t.high());
        assertEquals(40, t.n());
        Figure c = figure(r, s, "recovery rate CONTROL");
        assertEquals("10.00 %", c.value());
        assertEquals("3.96 %", c.low());
        Figure d = figure(r, s, "incremental recovery rate (TREATMENT − CONTROL)");
        assertEquals("20.00 pp", d.value());
        assertEquals("2.32 pp", d.low());
        assertEquals("36.57 pp", d.high());
        assertEquals(Figure.Status.OK, d.status());
        assertEquals(EvidenceLabel.SYNTHETIC, d.label());
        assertEquals(EvidenceLabel.SYNTHETIC, r.label());
        assertTrue(r.interim());
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("SYNTHETIC")));
        // 30 h captures: none by day 1, all by day 3
        assertEquals("0.00 %", figure(r, s, "cumulative recovery by day 1 TREATMENT").value());
        assertEquals("30.00 %", figure(r, s, "cumulative recovery by day 3 TREATMENT").value());
        // 40 of 80 CONTROL at 0.5: chi-square 0, p 1
        assertEquals("1.000000", figure(r, s, "sample-ratio mismatch (chi-square p; flagged below 0.001)").value());
    }

    @Test
    @DisplayName("Small n: no interval, 'insufficient data'; money below 100 per arm insufficient; cost TBD without a cost model")
    void insufficientDataAndTbd() {
        MetricsReport.Report r = report(snapshot("simulator", 20, 5, 10, 1, 30), null, null);
        String s = "holdout 5000 bp";
        Figure d = figure(r, s, "incremental recovery rate (TREATMENT − CONTROL)");
        assertEquals("insufficient data", d.value());
        assertNull(d.low());
        assertEquals(Figure.Status.INSUFFICIENT_DATA, d.status());
        assertEquals(30, d.n());
        assertEquals(Figure.Status.INSUFFICIENT_DATA, figure(r, s, "recovery rate CONTROL").status());
        assertEquals("insufficient data", figure(r, s, "incremental amount per eligible invoice (INR, TREATMENT − CONTROL)").value());
        assertEquals(EvidenceLabel.TBD, d.label(), "a number that was not computed is TBD (invariant 20)");
        Figure median = figure(r, s, "median time to recovery among recovered, CONTROL (hours; DESCRIPTIVE, not causal)");
        assertEquals("insufficient data", median.value(), "1 recovery: no median below 10");
        Figure cost = figure(r, s, "cost per incremental recovered rupee (decision metric)");
        assertEquals("TBD", cost.value());
        assertEquals(EvidenceLabel.TBD, cost.label());
        assertEquals("TBD", figure(r, "system health", "customers with invoices in both arms (interference)").value());
    }

    @Test
    @DisplayName("120 vs 120, 60 vs 30 recovered at 1 000 INR: money 500.00 − 250.00 = 250.00 INR per invoice (UNSTABLE < 300); cost per incremental rupee")
    void moneyAndCost() {
        MetricsReport.Report r = report(snapshot("simulator", 120, 60, 120, 30, 5), 500L, 240L);
        String s = "holdout 5000 bp";
        assertEquals("500.00", figure(r, s, "amount of recovered invoices per eligible invoice, TREATMENT (INR; failure amount, not captured amount)").value());
        assertEquals("250.00", figure(r, s, "amount of recovered invoices per eligible invoice, CONTROL (INR; failure amount, not captured amount)").value());
        Figure m = figure(r, s, "incremental amount per eligible invoice (INR, TREATMENT − CONTROL)");
        assertEquals("250.00", m.value());
        assertEquals(Figure.Status.UNSTABLE, m.status());
        assertNotNull(m.low());
        assertTrue(new java.math.BigDecimal(m.low()).compareTo(new java.math.BigDecimal(m.high())) < 0);
        // 120 contacts × 5.00 INR / 120 invoices = 5.00 INR per invoice; ÷ 250.00 INR incremental = 0.0200
        Figure cost = figure(r, s, "cost per incremental recovered rupee (decision metric)");
        assertEquals("0.0200", cost.value());
        assertEquals(Figure.Status.UNSTABLE, cost.status(), "inherits the money figure's instability");
        assertNotNull(cost.low());
        assertTrue(new java.math.BigDecimal(cost.low()).compareTo(new java.math.BigDecimal(cost.high())) < 0);
        // 120 contacts × 5.00 INR / 60 recovered × 1 000.00 INR
        assertEquals("0.0100", figure(r, s, "cost per observed recovered rupee (non-causal)").value());
        assertEquals("25.00 pp", figure(r, s, "incremental recovery rate (TREATMENT − CONTROL)").value());
        assertEquals("12.80 pp", figure(r, s, "incremental recovery rate (TREATMENT − CONTROL)").low());
        assertEquals(Figure.Status.UNSTABLE,
                figure(r, s, "amount of recovered invoices per eligible invoice, TREATMENT (INR; failure amount, not captured amount)").status());
        assertEquals("30000.00", figure(r, s, "north star: incremental amount (per-invoice difference × TREATMENT invoices, INR)").value());
        assertFalse(r.interim(), "240 mature invoices reached the pre-registered 240");
        assertEquals("5.0", figure(r, s, "median time to recovery among recovered, TREATMENT (hours; DESCRIPTIVE, not causal)").value());
        assertEquals("0.00 %", figure(r, s, "contact rate CONTROL (must be 0)").value());
        assertEquals("60", figure(r, s, "recoveries attributed MEND, TREATMENT").value());
        assertEquals("30", figure(r, s, "recoveries attributed LATE_CAPTURE, CONTROL").value());
    }

    @Test
    @DisplayName("Fail closed: razorpay-test data and an empty snapshot are SYNTHETIC, never MEASURED")
    void labelFailsClosed() {
        assertEquals(EvidenceLabel.SYNTHETIC, report(snapshot("razorpay-test", 40, 12, 40, 4, 30), null, null).label());
        assertEquals(EvidenceLabel.SYNTHETIC, report(snapshot("razorpay-live", 0, 0, 0, 0, 30), null, null).label());
    }

    @Test
    @DisplayName("Invoice-level ITT: a later campaign's recovery and contact count for the invoice's first arm (CONTROL → contamination ALERT)")
    void invoiceLevelOutcome() {
        List<CampaignFacts> facts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            facts.add(simple("t" + i, "TREATMENT", 5000, T0.plusSeconds(i), List.of(), false));
            facts.add(simple("c" + i, "CONTROL", 5000, T0.plusSeconds(100 + i), List.of(), false));
        }
        // invoice c0 also has a later campaign (holdout switched off, rate 0: TREATMENT) that MEND contacted and recovered
        CampaignFacts later = new CampaignFacts(MERCHANT, UUID.nameUUIDFromBytes("c0-later".getBytes()), "inv:inv_c0", null,
                T0.plus(Duration.ofDays(2)), new CampaignFacts.Assignment("TREATMENT", 0, 10_000, "HASH", 1, "h"), null, null,
                "CUSTOMER_ACTION_REQUIRED", "ALLOWED", null, List.of(T0.plus(Duration.ofDays(2)).plusSeconds(60)),
                List.of(new Capture(T0.plus(Duration.ofDays(3)), CaptureSource.MEND)), "MEND", "RECOVERED", 100_000L, "INR", 0, T0, false);
        facts.add(later);
        MetricsReport.Report r = report(write("multi", facts), null, null);
        String s = "holdout 5000 bp";
        assertEquals("2.50 %", rate(r, s, "recovery rate CONTROL"), "1 of 40 CONTROL invoices recovered (via its later campaign)");
        Figure contamination = figure(r, s, "contact rate CONTROL (must be 0)");
        assertEquals(Figure.Status.ALERT, contamination.status());
        assertEquals("1", figure(r, "system health", "invoices with more than one campaign (collapsed into one unit)").value());
        assertEquals("0", figure(r, "-", "invoices without a holdout (rate 0, no CONTROL): not part of any causal figure").value(),
                "the later rate-0 campaign is part of its invoice, not a unit of its own");
    }

    @Test
    @DisplayName("Strata are never pooled; an SRM mismatch is an ALERT")
    void strataAndSrm() {
        List<CampaignFacts> facts = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            facts.add(simple("a" + i, i < 70 ? "CONTROL" : "TREATMENT", 5000, T0.plusSeconds(i), List.of(), false));   // 70/80 CONTROL at 0.5
            facts.add(simple("b" + i, i < 16 ? "CONTROL" : "TREATMENT", 2000, T0.plusSeconds(500 + i), List.of(), false)); // 16/80 at 0.2
        }
        MetricsReport.Report r = report(write("strata", facts), null, null);
        assertEquals(Figure.Status.ALERT, figure(r, "holdout 5000 bp", "sample-ratio mismatch (chi-square p; flagged below 0.001)").status());
        assertEquals(Figure.Status.OK, figure(r, "holdout 2000 bp", "sample-ratio mismatch (chi-square p; flagged below 0.001)").status());
        assertEquals(80, figure(r, "holdout 2000 bp", "analysis status").n());
    }

    private CampaignFacts simple(String id, String arm, int rate, Instant eligible, List<Capture> captures, boolean contacted) {
        int propensity = "CONTROL".equals(arm) ? rate : 10_000 - rate;
        return new CampaignFacts(MERCHANT, UUID.nameUUIDFromBytes(id.getBytes()), "inv:inv_" + id, null, eligible,
                new CampaignFacts.Assignment(arm, rate, propensity, "HASH", 1, "h"), "INSUFFICIENT_FUNDS", "t", null, null, null,
                contacted ? List.of(eligible.plusSeconds(60)) : List.of(), captures, null, "ELIGIBLE", 100_000L, "INR", 0, T0, false);
    }

    private Path write(String name, List<CampaignFacts> facts) {
        SnapshotParams params = new SnapshotParams(T0, T0.plus(Duration.ofDays(3)), AS_OF, 14, null, "simulator", List.of());
        Path dir = tmp.resolve(name);
        SnapshotWriter.write(dir, name, Instant.EPOCH, params,
                SnapshotBuilder.build(facts, List.of(), new Pseudonyms(Map.of(MERCHANT, new byte[32])), params), List.of());
        return dir;
    }

    private static String rate(MetricsReport.Report r, String stratum, String name) {
        // below n 30 the arm shows no value, so read the note ("x of n")
        Figure f = figure(r, stratum, name);
        return f.status() == Figure.Status.OK ? f.value() : f.note();
    }

    @Test
    @DisplayName("Same snapshot + seed → identical report; razorpay-live data is MEASURED")
    void reproducibleAndLabelled() {
        Path dir = snapshot("razorpay-live", 40, 12, 40, 4, 30);
        assertEquals(ReportWriter.json(report(dir, null, null)), ReportWriter.json(report(dir, null, null)));
        assertEquals(EvidenceLabel.MEASURED, report(dir, null, null).label());
    }

    @Test
    @DisplayName("Tampered files are refused (sha256 mismatch); an INVALID snapshot is refused without --allow-invalid")
    void integrity() throws Exception {
        Path dir = snapshot("simulator", 40, 12, 40, 4, 30);
        Path campaigns = dir.resolve("campaigns.jsonl");
        Files.writeString(campaigns, Files.readString(campaigns).replace("\"recovered\":false", "\"recovered\":true"));
        assertThrows(IllegalStateException.class, () -> SnapshotFiles.read(dir, false));

        // an INVALID snapshot (a CONTROL invoice was contacted): refused, unless explicitly allowed, and then flagged
        CampaignFacts contacted = new CampaignFacts(MERCHANT, UUID.randomUUID(), "inv:inv_bad", null, T0,
                new CampaignFacts.Assignment("CONTROL", 5000, 5000, "HASH", 1, "h"), null, null, null, null, null,
                List.of(T0.plusSeconds(5)), List.of(), null, "ELIGIBLE", 100L, "INR", 0, T0, false);
        SnapshotParams params = new SnapshotParams(T0, T0.plus(Duration.ofDays(1)), AS_OF, 14, null, "simulator", List.of());
        Path bad = tmp.resolve("invalid");
        SnapshotWriter.Written w = SnapshotWriter.write(bad, "invalid", Instant.EPOCH, params,
                SnapshotBuilder.build(List.of(contacted), List.of(), new Pseudonyms(Map.of(MERCHANT, new byte[32])), params), List.of());
        assertFalse(w.valid());
        assertThrows(IllegalStateException.class, () -> SnapshotFiles.read(bad, false));
        MetricsReport.Report allowed = MetricsReport.compute(SnapshotFiles.read(bad, true), new MetricsReport.Options(7L, 2000, null, null));
        assertEquals("INVALID", allowed.snapshotStatus());
        assertTrue(allowed.notes().get(0).startsWith("Snapshot is INVALID"));
    }

    @Test
    @DisplayName("CLI writes <id>.md and <id>.json with every figure labelled")
    void cli() throws Exception {
        Path dir = snapshot("simulator", 40, 12, 40, 4, 30);
        Path out = tmp.resolve("out");
        Path md = ReportCli.run(Map.of("snapshot", dir.toString(), "out", out.toString(), "seed", "7"));
        String text = Files.readString(md);
        assertTrue(text.contains("**Label: SYNTHETIC**"));
        assertTrue(text.contains("INTERIM — not for decision"));
        assertTrue(Files.readString(out.resolve("snap-40-40.json")).contains("\"label\" : \"SYNTHETIC\""));
    }
}
