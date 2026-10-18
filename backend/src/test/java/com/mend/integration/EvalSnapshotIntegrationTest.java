package com.mend.integration;

import com.mend.controller.EvalSnapshotController;
import com.mend.domain.entity.*;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.*;
import com.mend.evaluation.snapshot.SnapshotCodec;
import com.mend.evaluation.snapshot.SnapshotRow;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.experiment.Arm;
import com.mend.experiment.HoldoutAssigner;
import com.mend.service.ClassificationService;
import com.mend.service.EvalSnapshotService;
import com.mend.service.PaymentReconciliationService;
import com.mend.taxonomy.ProviderFailure;
import com.mend.testsupport.TestRows;
import com.mend.testsupport.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
 * M4 U4.3 (ADR-004 §5): snapshots built from the real tables on PostgreSQL. Time is simulated by moving this test's own audit
 * rows back (the append-only experiment tables keep their real times, which are before as_of anyway).
 */
@SpringBootTest
@ActiveProfiles("test")
class EvalSnapshotIntegrationTest {

    @TempDir
    static Path snapshotDir;

    @DynamicPropertySource
    static void dir(DynamicPropertyRegistry registry) {
        registry.add("mend.eval.snapshot.dir", () -> snapshotDir.toString());
        registry.add("mend.eval.snapshot.settle-minutes", () -> "0");
    }

    @Autowired private ClassificationService classificationService;
    @Autowired private PaymentReconciliationService reconciliation;
    @Autowired private EvalSnapshotService snapshots;
    @Autowired private EvalSnapshotController controller;
    @Autowired private MerchantRepository merchants;
    @Autowired private MerchantConfigRepository merchantConfigs;
    @Autowired private CampaignRepository campaigns;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.mend.scheduler.ActionScheduler scheduler;

    private static final byte[] SALT = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            SALT[i] = (byte) (11 * i + 5);
        }
    }

    private UUID merchantId;
    private final List<String> rawValues = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M4 snapshot merchant"));
        jdbc.update("INSERT INTO merchant_experiment_salts (merchant_id, purpose, salt_version, salt) VALUES (?, 'ASSIGNMENT', 1, ?)",
                merchantId, SALT);
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setHoldoutRateBp(5000);
        merchantConfigs.save(config);
        rawValues.add(merchantId.toString());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')", merchantId);
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    private static String paymentIdIn(Arm arm) {
        for (int i = 0; i < 10_000; i++) {
            String payId = "pay_E" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            if (HoldoutAssigner.arm(HoldoutAssigner.bucket(SALT, "pay:" + payId), 5000) == arm) {
                return payId;
            }
        }
        throw new IllegalStateException("no key found");
    }

    private Campaign failPayment(String payId) {
        String externalId = "payment.failed_" + payId;
        WebhookEvent event = new WebhookEvent(UUID.randomUUID(), externalId, "payment.failed", "RAZORPAY",
                "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\",\"error_reason\":\"card_expired\"}}}}");
        event.setMerchantId(merchantId);
        webhooks.save(event);
        classificationService.classifyAndPersist(event, ProviderFailure.of(null, "card_expired", "customer"));
        Campaign campaign = campaigns.findByMerchantIdAndPaymentId(merchantId, externalId).orElseThrow();
        rawValues.addAll(List.of(campaign.getId().toString(), payId, campaign.getInvoiceKey(), externalId));
        return campaign;
    }

    private void lateCapture(String payId) {
        WebhookEvent captured = new WebhookEvent(UUID.randomUUID(), "payment.captured_" + payId, "payment.captured", "RAZORPAY",
                "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId
                        + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"captured\"}}}}");
        captured.setMerchantId(merchantId);
        captured.setProcessingStatus(WebhookEventStatus.VERIFIED);
        reconciliation.reconcileWebhookEvent(webhooks.save(captured));
    }

    /** Moves this merchant's audit history back (simulated time; only rows this test wrote). */
    private void ageAudit(Duration by) {
        jdbc.update("UPDATE audit_logs SET created_at = created_at - make_interval(secs => ?) WHERE merchant_id = ?", (double) by.toSeconds(), merchantId);
    }

    private EvalSnapshotService.Request request(Instant asOf) {
        return new EvalSnapshotService.Request(asOf.minus(Duration.ofDays(30)), asOf, asOf, 1, List.of(merchantId));
    }

    private static Map<String, SnapshotRow> byArm(List<SnapshotRow> rows) {
        Map<String, SnapshotRow> out = new HashMap<>();
        rows.forEach(r -> out.put(r.arm(), r));
        return out;
    }

    @Test
    @DisplayName("TREATMENT + CONTROL: VALID snapshot; CONTROL's late capture counts as recovered; TREATMENT contacted; files PII-free")
    void validSnapshot() throws Exception {
        Campaign treatment = failPayment(paymentIdIn(Arm.TREATMENT));
        String controlPay = paymentIdIn(Arm.CONTROL);
        Campaign control = failPayment(controlPay);
        lateCapture(controlPay);
        jdbc.update("UPDATE action_intents SET scheduled_at = now() - interval '1 minute', status = 'READY' WHERE campaign_id = ?", treatment.getId());
        scheduler.pollAndExecuteDueActions();   // real dispatch (mock provider): a DISPATCHED decision row
        ageAudit(Duration.ofDays(3));
        Instant asOf = Instant.now();

        EvalSnapshot s = snapshots.createSnapshot(request(asOf));

        assertEquals(EvalSnapshot.Status.VALID, s.getStatus(), String.valueOf(s.getErrors()));
        assertNotNull(jdbc.queryForObject("SELECT installed_on FROM flyway_schema_history WHERE version = '22'", java.sql.Timestamp.class));
        assertEquals(2, s.getRowCount());
        assertEquals("mock", s.getProviderMode());
        Path dir = snapshotDir.resolve(s.getId().toString());
        String campaignsJsonl = Files.readString(dir.resolve("campaigns.jsonl"));
        String decisionsJsonl = Files.readString(dir.resolve("decisions.jsonl"));
        assertEquals(s.getSha256(), SnapshotCodec.snapshotSha256(campaignsJsonl, decisionsJsonl));
        try (var entries = Files.list(snapshotDir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith(".tmp-")), "published atomically, no temp dir left");
        }
        assertTrue(Files.exists(dir.resolve("campaigns.csv")));
        assertTrue(Files.readString(dir.resolve("manifest.json")).contains(s.getSha256()));

        Map<String, SnapshotRow> rows = byArm(SnapshotCodec.parseCampaigns(campaignsJsonl));
        SnapshotRow c = rows.get("CONTROL");
        assertTrue(c.mature());
        assertTrue(c.recovered());
        assertEquals("ORIGINAL", c.captureSource());
        assertFalse(c.contacted());
        assertEquals(5000, c.propensityBp());
        assertEquals(49_900L, c.amountMinor());
        SnapshotRow t = rows.get("TREATMENT");
        assertTrue(t.contacted());
        assertEquals(1, t.contactCount());
        assertFalse(t.recovered());
        assertEquals("CUSTOMER_ACTION_REQUIRED", t.strategy());
        assertEquals("SYNTHETIC", t.evidenceLabel(), "mock provider data is SYNTHETIC");
        assertTrue(decisionsJsonl.contains("\"decision_point\":\"ASSIGNMENT\""));

        String everything = campaignsJsonl + decisionsJsonl + Files.readString(dir.resolve("campaigns.csv"))
                + Files.readString(dir.resolve("manifest.json"));
        for (String raw : rawValues) {
            assertFalse(everything.contains(raw), "raw value in snapshot files: " + raw);
        }
        assertFalse(everything.contains(control.getId().toString()));
    }

    @Test
    @DisplayName("Re-running the same window and as_of returns the same snapshot (same sha256), also after later activity")
    void idempotentAndPointInTime() {
        failPayment(paymentIdIn(Arm.TREATMENT));
        String controlPay = paymentIdIn(Arm.CONTROL);
        failPayment(controlPay);
        ageAudit(Duration.ofDays(3));
        Instant asOf = Instant.now();
        EvalSnapshot first = snapshots.createSnapshot(request(asOf));

        lateCapture(controlPay);               // after as_of
        failPayment(paymentIdIn(Arm.TREATMENT)); // eligible after as_of
        EvalSnapshot again = snapshots.createSnapshot(request(asOf));

        assertEquals(first.getSha256(), again.getSha256());
        assertEquals(first.getId(), again.getId());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eval_snapshots WHERE sha256 = ?", Integer.class, first.getSha256()));

        EvalSnapshot later = snapshots.createSnapshot(request(Instant.now()));
        assertNotEquals(first.getSha256(), later.getSha256(), "a later as_of sees the capture and the new campaign");
    }

    @Test
    @DisplayName("Merchant filter: another merchant's campaigns never appear; an unknown merchant is refused; missing files are rewritten on reuse")
    void tenantScopedAndSelfHealing() throws Exception {
        UUID other = UUID.randomUUID();
        merchants.save(new Merchant(other, "M4 other merchant"));
        try {
            failPayment(paymentIdIn(Arm.TREATMENT));
            UUID mine = merchantId;
            merchantId = other;
            jdbc.update("INSERT INTO merchant_experiment_salts (merchant_id, purpose, salt_version, salt) VALUES (?, 'ASSIGNMENT', 1, ?)", other, SALT);
            failPayment(paymentIdIn(Arm.TREATMENT));
            ageAudit(Duration.ofDays(3));
            merchantId = mine;
            ageAudit(Duration.ofDays(3));
            Instant asOf = Instant.now();
            EvalSnapshot s = snapshots.createSnapshot(request(asOf));
            assertEquals(1, s.getRowCount(), "only the filtered merchant");

            assertThrows(IllegalArgumentException.class, () -> snapshots.createSnapshot(
                    new EvalSnapshotService.Request(asOf.minus(Duration.ofDays(30)), asOf, asOf, 1, List.of(UUID.randomUUID()))));

            Path dir = snapshotDir.resolve(s.getId().toString());
            Files.delete(dir.resolve("campaigns.jsonl"));
            EvalSnapshot again = snapshots.createSnapshot(request(asOf));
            assertEquals(s.getId(), again.getId());
            assertEquals(s.getSha256(), SnapshotCodec.snapshotSha256(Files.readString(dir.resolve("campaigns.jsonl")),
                    Files.readString(dir.resolve("decisions.jsonl"))));
        } finally {
            TestRows.deleteMerchant(jdbc, other);
        }
    }

    @Test
    @DisplayName("A RECOVERED campaign without attribution makes the snapshot INVALID; the defect is stored, nothing is fixed")
    void invalidSnapshotRecorded() {
        Campaign campaign = failPayment(paymentIdIn(Arm.CONTROL));
        // a pre-V15-style recovery: RECOVERED with no attribution (simulated through the audit trail only)
        jdbc.update("INSERT INTO audit_logs (id, merchant_id, campaign_id, event_type, actor_type, metadata, created_at) "
                + "VALUES (?, ?, ?, 'CAMPAIGN_STATE_TRANSITION', 'SYSTEM', '{\"newState\":\"RECOVERED\"}'::jsonb, now())",
                UUID.randomUUID(), merchantId, campaign.getId());
        ageAudit(Duration.ofDays(3));
        EvalSnapshot s = snapshots.createSnapshot(request(Instant.now()));

        assertEquals(EvalSnapshot.Status.INVALID, s.getStatus());
        assertEquals("RECOVERED_WITHOUT_ATTRIBUTION", s.getErrors().get(0).get("code"));
        assertNull(campaigns.findById(campaign.getId()).orElseThrow().getRecoveryAttribution(), "data is not repaired");
    }

    @Test
    @DisplayName("eval_snapshots rows are immutable; only SYSTEM_ADMIN may create snapshots; a future as_of is refused")
    void immutableAndGuarded() {
        failPayment(paymentIdIn(Arm.TREATMENT));
        ageAudit(Duration.ofDays(3));
        EvalSnapshot s = snapshots.createSnapshot(request(Instant.now()));
        DataAccessException e = assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE eval_snapshots SET status = 'VALID', errors = '[]'::jsonb WHERE id = ?", s.getId()));
        assertTrue(e.getMostSpecificCause().getMessage().contains("APPEND_ONLY"));

        var body = new EvalSnapshotController.CreateSnapshotRequest(Instant.now().minus(Duration.ofDays(1)), Instant.now(), null, 1, List.of(merchantId));
        assertThrows(TenantAccessDeniedException.class, () -> controller.create(body, TestUsers.withRole(merchantId, "MERCHANT_ADMIN")));
        assertThrows(IllegalArgumentException.class, () -> snapshots.createSnapshot(
                new EvalSnapshotService.Request(Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)), null, 1, List.of(merchantId))));
        assertEquals(201, controller.create(body, TestUsers.systemAdmin()).getStatusCode().value());
    }
}
