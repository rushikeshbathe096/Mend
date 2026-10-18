package com.mend.integration;

import com.mend.domain.entity.*;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.*;
import com.mend.evaluation.EvidenceLabel;
import com.mend.evaluation.metrics.Figure;
import com.mend.evaluation.metrics.MetricsReport;
import com.mend.evaluation.metrics.SnapshotFiles;
import com.mend.evaluation.sim.ReplayEvent;
import com.mend.evaluation.sim.SyntheticGenerator;
import com.mend.evaluation.snapshot.SnapshotCodec;
import com.mend.evaluation.snapshot.SnapshotRow;
import com.mend.scheduler.ActionScheduler;
import com.mend.service.ClassificationService;
import com.mend.service.EvalSnapshotService;
import com.mend.service.PaymentReconciliationService;
import com.mend.testsupport.TestRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 U4.6: the whole evaluation chain on the real database pipeline (isolated test database, mock provider: SYNTHETIC).
 * Seeded synthetic failures go through webhook classification → taxonomy → holdout assignment (rate 0.2) → strategy →
 * compliance → intent → scheduler dispatch; seeded customer outcomes arrive as provider webhooks (late captures in both arms,
 * paid links in TREATMENT); then snapshot (U4.3) → metrics (U4.4). The figures must agree with the database.
 */
@SpringBootTest
@ActiveProfiles("test")
class SyntheticExperimentEndToEndTest {

    @TempDir
    static Path snapshotDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("mend.eval.snapshot.dir", () -> snapshotDir.toString());
        registry.add("mend.eval.snapshot.settle-minutes", () -> "0");
    }

    @Autowired private ClassificationService classificationService;
    @Autowired private PaymentReconciliationService reconciliation;
    @Autowired private ActionScheduler scheduler;
    @Autowired private EvalSnapshotService snapshots;
    @Autowired private MerchantRepository merchants;
    @Autowired private MerchantConfigRepository merchantConfigs;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private ActionIntentRepository intents;
    @Autowired private JdbcTemplate jdbc;

    private static final long SEED = 20261019L;
    private static final int SIZE = 150;
    private UUID merchantId;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantId = UUID.nameUUIDFromBytes(("e2e:" + SEED + ":" + UUID.randomUUID()).getBytes());
        merchants.save(new Merchant(merchantId, "M4 synthetic experiment merchant"));
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setHoldoutRateBp(2000);
        merchantConfigs.save(config);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')", merchantId);
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    private WebhookEvent webhook(String externalId, String type, String payload) {
        WebhookEvent e = new WebhookEvent(UUID.randomUUID(), externalId, type, "RAZORPAY", payload);
        e.setMerchantId(merchantId);
        e.setProcessingStatus(WebhookEventStatus.VERIFIED);
        return webhooks.save(e);
    }

    @Test
    @DisplayName("Seeded failures → real pipeline (holdout 0.2) → seeded outcomes → VALID snapshot → report consistent with the database")
    void endToEnd() throws Exception {
        List<ReplayEvent> events = SyntheticGenerator.generate(SEED, SIZE, merchantId, Instant.now(), Duration.ofMinutes(1));
        SplittableRandom outcomes = new SplittableRandom(SEED);
        List<String> payIds = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            ReplayEvent e = events.get(i);
            String payId = "pay_SYN" + Long.toHexString(SEED) + String.format("%05d", i) + UUID.randomUUID().toString().substring(0, 4);
            payIds.add(payId);
            WebhookEvent failed = webhook("payment.failed_" + payId, "payment.failed",
                    "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId + "\",\"amount\":" + e.amountMinor()
                            + ",\"currency\":\"INR\",\"status\":\"failed\",\"error_reason\":\"" + e.failure().reason() + "\"}}}}");
            classificationService.classifyAndPersist(failed, e.failure());
        }
        jdbc.update("UPDATE action_intents SET scheduled_at = now() - interval '1 minute', status = 'READY' "
                + "WHERE merchant_id = ? AND status IN ('SCHEDULED','PENDING','READY')", merchantId);
        scheduler.pollAndExecuteDueActions();
        scheduler.pollAndExecuteDueActions();

        // Seeded customer behaviour (SYNTHETIC): 10 % pay the original payment late (any arm); 30 % of links get paid.
        for (String payId : payIds) {
            if (outcomes.nextDouble() < 0.10) {
                reconciliation.reconcileWebhookEvent(webhook("payment.captured_" + payId, "payment.captured",
                        "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId
                                + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"captured\"}}}}"));
            }
        }
        for (ActionIntent link : intents.findAll().stream().filter(i -> merchantId.equals(i.getMerchantId())
                && i.getStatus() == ActionIntentStatus.AWAITING_PAYMENT && i.getResponseReference() != null).toList()) {
            if (outcomes.nextDouble() < 0.30) {
                String plink = link.getResponseReference();
                reconciliation.reconcileWebhookEvent(webhook("payment_link.paid_" + plink, "payment_link.paid",
                        "{\"event\":\"payment_link.paid\",\"payload\":{\"payment_link\":{\"entity\":{\"id\":\"" + plink + "\",\"status\":\"paid\"}},"
                                + "\"payment\":{\"entity\":{\"id\":\"pay_L" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                                + "\",\"status\":\"captured\"}}}}"));
            }
        }
        // Simulated time: the history is 20 days old, so every invoice is mature under a 14-day horizon.
        jdbc.update("UPDATE audit_logs SET created_at = created_at - interval '20 days' WHERE merchant_id = ?", merchantId);
        Instant asOf = Instant.now();

        EvalSnapshot snapshot = snapshots.createSnapshot(new EvalSnapshotService.Request(asOf.minus(Duration.ofDays(30)), asOf, asOf, 14,
                List.of(merchantId)));
        assertEquals(EvalSnapshot.Status.VALID, snapshot.getStatus(), String.valueOf(snapshot.getErrors()));

        // the database's own counts
        int eligible = jdbc.queryForObject("SELECT count(*) FROM holdout_assignments WHERE merchant_id = ?", Integer.class, merchantId);
        int control = jdbc.queryForObject("SELECT count(*) FROM holdout_assignments WHERE merchant_id = ? AND arm = 'CONTROL'", Integer.class, merchantId);
        int recovered = jdbc.queryForObject("SELECT count(*) FROM campaigns WHERE merchant_id = ? AND current_state = 'RECOVERED'", Integer.class, merchantId);
        int controlIntents = jdbc.queryForObject("SELECT count(*) FROM action_intents i JOIN holdout_assignments a ON a.campaign_id = i.campaign_id "
                + "WHERE a.merchant_id = ? AND a.arm = 'CONTROL'", Integer.class, merchantId);
        assertEquals(0, controlIntents, "CONTROL never gets an intent");
        assertTrue(control > 0 && control < eligible);

        Path dir = snapshotDir.resolve(snapshot.getId().toString());
        List<SnapshotRow> rows = SnapshotCodec.parseCampaigns(Files.readString(dir.resolve("campaigns.jsonl")));
        assertEquals(eligible, rows.size());
        assertEquals(control, rows.stream().filter(r -> "CONTROL".equals(r.arm())).count());
        assertEquals(recovered, rows.stream().filter(SnapshotRow::invoiceRecovered).count());
        assertTrue(rows.stream().allMatch(r -> "SYNTHETIC".equals(r.evidenceLabel())), "mock provider data is SYNTHETIC");

        MetricsReport.Report report = MetricsReport.compute(SnapshotFiles.read(dir, false), new MetricsReport.Options(SEED, 2000, null, null));
        assertEquals(EvidenceLabel.SYNTHETIC, report.label());
        String stratum = "holdout 2000 bp";
        Figure controlContacts = report.figures().stream().filter(f -> f.scope().equals("overall") && f.stratum().equals(stratum)
                && f.name().equals("contact rate CONTROL (must be 0)")).findFirst().orElseThrow();
        assertEquals("0.00 %", controlContacts.value());
        assertEquals(control, controlContacts.n());
        Figure srm = report.figures().stream().filter(f -> f.stratum().equals(stratum) && f.name().startsWith("sample-ratio")).findFirst().orElseThrow();
        assertNotEquals(Figure.Status.ALERT, srm.status(), "assignment at 0.2 is not a sample-ratio mismatch");
        assertEquals(eligible, srm.n());
        assertTrue(report.interim());
    }
}
