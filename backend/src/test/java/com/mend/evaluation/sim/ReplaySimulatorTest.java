package com.mend.evaluation.sim;

import com.mend.compliance.ComplianceEngine;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.ClassificationResult;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.RecoveryStrategy;
import com.mend.evaluation.snapshot.SnapshotCodec;
import com.mend.evaluation.snapshot.SnapshotWriter;
import com.mend.experiment.Arm;
import com.mend.strategy.RecoveryContext;
import com.mend.strategy.RecoveryStrategyEngine;
import com.mend.taxonomy.FailureTaxonomy;
import com.mend.taxonomy.PaymentFailureClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** M4 U4.5 (invariant 25): reproducible, in-memory, and driven by the production policy classes. */
class ReplaySimulatorTest {

    private static final UUID MERCHANT = UUID.nameUUIDFromBytes("sim-test".getBytes());
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(28);

    private static ReplaySimulator.Result run(long seed, int size, int holdout, PolicyVariant variant) {
        return new ReplaySimulator().run(SyntheticGenerator.generate(seed, size, MERCHANT, START, WINDOW),
                new ReplaySimulator.Config(seed, holdout, 14, variant, OutcomeModel.defaults(), START, WINDOW));
    }

    private static String sha(ReplaySimulator.Result r) {
        return SnapshotWriter.prepare("x", r.content(), r.params()).sha256();
    }

    @Test
    @DisplayName("Same seed → the same events, outcomes and snapshot checksum; another seed → different")
    void reproducible() {
        assertEquals(SyntheticGenerator.generate(5, 500, MERCHANT, START, WINDOW), SyntheticGenerator.generate(5, 500, MERCHANT, START, WINDOW));
        assertEquals(sha(run(5, 500, 2000, PolicyVariant.B_CURRENT)), sha(run(5, 500, 2000, PolicyVariant.B_CURRENT)));
        assertNotEquals(sha(run(5, 500, 2000, PolicyVariant.B_CURRENT)), sha(run(6, 500, 2000, PolicyVariant.B_CURRENT)));
    }

    @Test
    @DisplayName("The policy objects are the production classes (not subclasses or copies), and agree with a direct call")
    void productionClasses() {
        ReplaySimulator sim = new ReplaySimulator();
        assertSame(RecoveryStrategyEngine.class, sim.strategyEngine.getClass());
        assertSame(ComplianceEngine.class, sim.complianceEngine.getClass());

        ReplaySimulator.Result r = run(9, 300, 0, PolicyVariant.B_CURRENT);
        for (ReplaySimulator.Outcome o : r.outcomes().stream().filter(ReplaySimulator.Outcome::eligible).limit(50).toList()) {
            Campaign c = new Campaign(o.event().campaignId(), MERCHANT);
            c.setCurrentState(CampaignStatus.ELIGIBLE);
            ClassificationResult cr = new ClassificationResult(null, null, c.getId(),
                    FailureTaxonomy.classify(o.event().failure()).failureClass().name(), null, null, null, null);
            cr.setTaxonomyVersion(FailureTaxonomy.TABLE_VERSION);
            com.mend.domain.entity.MerchantConfig config = new com.mend.domain.entity.MerchantConfig(UUID.randomUUID(), MERCHANT);
            assertEquals(new RecoveryStrategyEngine().evaluate(new RecoveryContext(c, cr, config)).getStrategy(), o.strategy());
        }
    }

    @Test
    @DisplayName("In memory only: no field of the simulator types refers to Spring, JDBC or a repository")
    void noDatabase() {
        for (Class<?> type : List.of(ReplaySimulator.class, SyntheticGenerator.class, OutcomeModel.class, PolicyComparison.class)) {
            for (Field f : type.getDeclaredFields()) {
                String name = f.getType().getName();
                assertFalse(name.startsWith("org.springframework") || name.startsWith("javax.sql") || name.startsWith("java.sql")
                        || name.contains("Repository") || name.contains("EntityManager"), type.getSimpleName() + "." + f.getName());
            }
        }
    }

    @Test
    @DisplayName("CONTROL is never contacted; decision B gives UNCLASSIFIED a link (never a retry); policy A makes it ineligible")
    void policies() {
        ReplaySimulator.Result b = run(11, 2000, 2000, PolicyVariant.B_CURRENT);
        assertTrue(b.outcomes().stream().filter(o -> o.arm() == Arm.CONTROL).noneMatch(ReplaySimulator.Outcome::contacted));
        List<ReplaySimulator.Outcome> unclassifiedT = b.outcomes().stream()
                .filter(o -> o.failureClass() == PaymentFailureClass.UNCLASSIFIED && o.arm() == Arm.TREATMENT).toList();
        assertFalse(unclassifiedT.isEmpty());
        assertTrue(unclassifiedT.stream().allMatch(o -> o.strategy() == RecoveryStrategy.CUSTOMER_ACTION_REQUIRED && o.contacted()));

        ReplaySimulator.Result a = run(11, 2000, 2000, PolicyVariant.A_UNCLASSIFIED_INELIGIBLE);
        assertTrue(a.outcomes().stream().filter(o -> o.failureClass() == PaymentFailureClass.UNCLASSIFIED).noneMatch(ReplaySimulator.Outcome::eligible));
        // U4.5 review: as in production (Razorpay mode) an allowed retry is dispatched (a contact) but NotSupported: never a
        // MEND capture unless retries are wired
        List<ReplaySimulator.Outcome> retries = b.outcomes().stream()
                .filter(o -> o.strategy() == RecoveryStrategy.RETRY_LATER || o.strategy() == RecoveryStrategy.RETRY_IMMEDIATELY).toList();
        assertFalse(retries.isEmpty());
        assertTrue(retries.stream().anyMatch(ReplaySimulator.Outcome::contacted));
        assertTrue(retries.stream().noneMatch(o -> o.source() == com.mend.evaluation.snapshot.CampaignFacts.CaptureSource.MEND));
    }

    @Test
    @DisplayName("Common random numbers: a failure outside UNCLASSIFIED has the same outcome under A and B")
    void pairedDraws() {
        ReplaySimulator.Result a = run(13, 1000, 0, PolicyVariant.A_UNCLASSIFIED_INELIGIBLE);
        ReplaySimulator.Result b = run(13, 1000, 0, PolicyVariant.B_CURRENT);
        for (int i = 0; i < 1000; i++) {
            if (a.outcomes().get(i).failureClass() != PaymentFailureClass.UNCLASSIFIED) {
                assertEquals(a.outcomes().get(i).firstCapture(), b.outcomes().get(i).firstCapture());
            }
        }
        PolicyComparison.Comparison c = PolicyComparison.compare("A", a, "B", b, 2000, 1L);
        assertTrue(c.b().recoveredMinor() >= c.a().recoveredMinor(), "B adds links, never removes a recovery");
    }

    @Test
    @DisplayName("No action after recovery: a contacted campaign's original capture always comes after its contact")
    void noActionAfterRecovery() {
        ReplaySimulator.Result r = new ReplaySimulator().run(SyntheticGenerator.generate(19, 3000, MERCHANT, START, WINDOW),
                new ReplaySimulator.Config(19, 0, 14, PolicyVariant.B_CURRENT, OutcomeModel.defaults().withRetries(true, 0.15), START, WINDOW, 48, null));
        assertTrue(r.content().campaigns().stream().anyMatch(row -> row.contacted() && "ORIGINAL".equals(row.captureSource())));
        assertTrue(r.content().campaigns().stream().noneMatch(row -> row.contacted() && row.captureOffsetS() != null
                && "ORIGINAL".equals(row.captureSource()) && row.captureOffsetS() <= row.firstContactOffsetS()));
    }

    @Test
    @DisplayName("Retry delay comes from production's rule: RETRY_LATER is contacted after the merchant's contact window")
    void retryDelayFromProduction() {
        ReplaySimulator.Result r = new ReplaySimulator().run(SyntheticGenerator.generate(23, 1500, MERCHANT, START, WINDOW),
                new ReplaySimulator.Config(23, 0, 14, PolicyVariant.B_CURRENT, OutcomeModel.defaults(), START, WINDOW, 48, null));
        assertTrue(r.content().campaigns().stream().filter(row -> "RETRY_LATER".equals(row.strategy()) && row.contacted())
                .allMatch(row -> row.firstContactOffsetS() == 48 * 3600 + 60));
        assertTrue(r.content().campaigns().stream().anyMatch(row -> "RETRY_LATER".equals(row.strategy()) && row.contacted()));
    }

    @Test
    @DisplayName("A merchant restriction yields MANUAL_REVIEW from the production engine and no contact")
    void restrictedMerchantConfig() {
        ReplaySimulator.Result r = new ReplaySimulator().run(SyntheticGenerator.generate(29, 500, MERCHANT, START, WINDOW),
                new ReplaySimulator.Config(29, 0, 14, PolicyVariant.B_CURRENT, OutcomeModel.defaults(), START, WINDOW, null, "RETRY_LATER"));
        List<ReplaySimulator.Outcome> review = r.outcomes().stream().filter(o -> o.strategy() == RecoveryStrategy.MANUAL_REVIEW).toList();
        assertFalse(review.isEmpty());
        assertTrue(review.stream().noneMatch(ReplaySimulator.Outcome::contacted));
    }

    @Test
    @DisplayName("Reused invoices: one arm per invoice (INVOICE_REUSE) and one link in flight (DUPLICATE_ACTION)")
    void reusedInvoices() {
        ReplaySimulator.Result r = new ReplaySimulator().run(SyntheticGenerator.generate(31, 3000, MERCHANT, START, WINDOW, 0.25),
                new ReplaySimulator.Config(31, 2000, 14, PolicyVariant.B_CURRENT, OutcomeModel.defaults(), START, WINDOW));
        Map<String, java.util.Set<String>> armsByInvoice = r.content().campaigns().stream().collect(Collectors.groupingBy(
                com.mend.evaluation.snapshot.SnapshotRow::invoiceRef, Collectors.mapping(com.mend.evaluation.snapshot.SnapshotRow::arm, Collectors.toSet())));
        assertTrue(armsByInvoice.values().stream().allMatch(arms -> arms.size() == 1));
        assertTrue(r.content().campaigns().stream().anyMatch(row -> "INVOICE_REUSE".equals(row.assignmentSource())));
        assertTrue(r.content().campaigns().stream().anyMatch(row -> "DUPLICATE_ACTION".equals(row.complianceReason())));
        assertTrue(SnapshotWriter.prepare("x", r.content(), r.params()).valid());
    }

    @Test
    @DisplayName("Snapshot replay: stored class and version are used; a legacy row (no taxonomy version) fails closed")
    void snapshotReplay() {
        ReplaySimulator.Result original = run(37, 400, 2000, PolicyVariant.B_CURRENT);
        List<ReplayEvent> events = new java.util.ArrayList<>(SnapshotReplay.events(original.content().campaigns(), MERCHANT));
        ReplayEvent legacy = new ReplayEvent(MERCHANT, UUID.randomUUID(), "ref:legacy", null, "INSUFFICIENT_FUNDS", null, 100L, START);
        events.add(legacy);
        ReplaySimulator.Result replay = new ReplaySimulator().run(events,
                new ReplaySimulator.Config(37, 0, 14, PolicyVariant.B_CURRENT, OutcomeModel.defaults(), START, WINDOW));
        assertEquals(events.size(), replay.outcomes().size());
        assertFalse(replay.outcomes().get(events.size() - 1).eligible(), "legacy row is not eligible");
        assertEquals(original.content().campaigns().size(), replay.outcomes().stream().filter(ReplaySimulator.Outcome::eligible).count());
    }

    @Test
    @DisplayName("SimulateCli end to end: same seed → identical files; report and comparison labelled SYNTHETIC")
    void cliIsReproducibleAndLabelled(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        Map<String, String> args = Map.of("seed", "41", "size", "800", "resamples", "2000");
        List<java.nio.file.Path> fa = com.mend.evaluation.cli.SimulateCli.run(withOut(args, tmp.resolve("a")));
        List<java.nio.file.Path> fb = com.mend.evaluation.cli.SimulateCli.run(withOut(args, tmp.resolve("b")));
        for (int i = 1; i < fa.size(); i++) {
            assertEquals(java.nio.file.Files.readString(fa.get(i)), java.nio.file.Files.readString(fb.get(i)), fa.get(i).getFileName().toString());
        }
        assertTrue(java.nio.file.Files.readString(fa.get(1)).contains("**Label: SYNTHETIC**"));
        String policies = java.nio.file.Files.readString(fa.get(2));
        assertTrue(policies.contains("**Label: SYNTHETIC**"));
        assertTrue(policies.contains("Monte Carlo noise"));
        assertFalse(policies.contains("MEASURED"));
    }

    private static Map<String, String> withOut(Map<String, String> args, java.nio.file.Path out) {
        Map<String, String> m = new java.util.HashMap<>(args);
        m.put("out", out.toString());
        return m;
    }

    @Test
    @DisplayName("Generated snapshot is VALID and SYNTHETIC; the generator follows its documented weights (±3 points at n = 20 000)")
    void snapshotAndDistributions() {
        ReplaySimulator.Result r = run(17, 3000, 2000, PolicyVariant.B_CURRENT);
        SnapshotWriter.Written w = SnapshotWriter.prepare("x", r.content(), r.params());
        assertTrue(w.valid(), w.defects().toString());
        assertTrue(SnapshotCodec.campaignsJsonl(r.content().campaigns()).contains("\"evidence_label\":\"SYNTHETIC\""));
        assertFalse(SnapshotCodec.campaignsJsonl(r.content().campaigns()).contains("\"evidence_label\":\"MEASURED\""));

        Map<String, Long> reasons = SyntheticGenerator.generate(3, 20_000, MERCHANT, START, WINDOW).stream()
                .collect(Collectors.groupingBy(e -> e.failure().reason(), Collectors.counting()));
        for (SyntheticGenerator.Weighted w2 : SyntheticGenerator.REASONS) {
            assertEquals(w2.weight(), reasons.get(w2.value()) / 20_000.0, 0.03, w2.value());
        }
    }
}
