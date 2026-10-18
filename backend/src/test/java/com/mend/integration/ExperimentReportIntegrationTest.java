package com.mend.integration;

import com.mend.domain.entity.EvalSnapshot;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.MerchantConfig;
import com.mend.domain.entity.WebhookEvent;
import com.mend.domain.repository.MerchantConfigRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.WebhookEventRepository;
import com.mend.dto.ExperimentSummaryDto;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.service.ClassificationService;
import com.mend.service.EvalSnapshotService;
import com.mend.service.ExperimentReportService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** M4 U4.8(c): the merchant's experiment view — operational arm counts, causal figures only from a VALID snapshot (else TBD). */
@SpringBootTest
@ActiveProfiles("test")
class ExperimentReportIntegrationTest {

    @TempDir
    static Path snapshotDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("mend.eval.snapshot.dir", () -> snapshotDir.toString());
        registry.add("mend.eval.snapshot.settle-minutes", () -> "0");
    }

    @Autowired private ExperimentReportService experiments;
    @Autowired private EvalSnapshotService snapshots;
    @Autowired private ClassificationService classificationService;
    @Autowired private MerchantRepository merchants;
    @Autowired private MerchantConfigRepository merchantConfigs;
    @Autowired private WebhookEventRepository webhooks;
    @Autowired private JdbcTemplate jdbc;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE action_intents SET scheduled_at = now() + interval '30 days' WHERE status IN ('READY','SCHEDULED','PENDING')");
        merchantId = UUID.randomUUID();
        merchants.save(new Merchant(merchantId, "M4 experiment view merchant"));
        MerchantConfig config = new MerchantConfig(UUID.randomUUID(), merchantId);
        config.setHoldoutRateBp(5000);
        merchantConfigs.save(config);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE action_intents SET status = 'CANCELLED', completed_at = now() WHERE merchant_id = ? "
                + "AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')", merchantId);
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    @Test
    @DisplayName("Without a snapshot the causal figure is TBD; after a VALID snapshot the figures come from it, labelled, with n")
    void figuresOnlyFromAValidSnapshot() {
        for (int i = 0; i < 12; i++) {
            String payId = "pay_X" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            WebhookEvent e = new WebhookEvent(UUID.randomUUID(), "payment.failed_" + payId, "payment.failed", "RAZORPAY",
                    "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + payId
                            + "\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"failed\",\"error_reason\":\"card_expired\"}}}}");
            e.setMerchantId(merchantId);
            classificationService.classifyAndPersist(webhooks.save(e), ProviderFailure.of(null, "card_expired", "customer"));
        }
        ExperimentSummaryDto before = experiments.forMerchant(merchantId, TestUsers.memberOf(merchantId));
        assertEquals(5000, before.holdoutRateBp());
        assertEquals(12, before.assignedTreatment() + before.assignedControl());
        assertNull(before.snapshot());
        assertEquals("TBD", before.figures().get(0).value());
        assertEquals("TBD", before.figures().get(0).label());

        jdbc.update("UPDATE audit_logs SET created_at = created_at - interval '20 days' WHERE merchant_id = ?", merchantId);
        Instant asOf = Instant.now();
        EvalSnapshot s = snapshots.createSnapshot(new EvalSnapshotService.Request(asOf.minus(Duration.ofDays(30)), asOf, asOf, 14, List.of(merchantId)));
        assertEquals(EvalSnapshot.Status.VALID, s.getStatus(), String.valueOf(s.getErrors()));

        ExperimentSummaryDto after = experiments.forMerchant(merchantId, TestUsers.memberOf(merchantId));
        assertNotNull(after.snapshot());
        assertEquals(s.getId(), after.snapshot().id());
        assertEquals("SYNTHETIC", after.snapshot().label(), "mock-provider data");
        ExperimentSummaryDto.FigureDto incremental = after.figures().stream()
                .filter(f -> f.name().equals("incremental recovery rate (TREATMENT − CONTROL)")).findFirst().orElseThrow();
        assertEquals("insufficient data", incremental.value(), "12 invoices: below the minimum n");
        assertEquals("TBD", incremental.label());
        assertEquals(12, incremental.n());
        assertTrue(after.figures().stream().anyMatch(f -> f.name().equals("contact rate CONTROL (must be 0)")));
    }

    @Test
    @DisplayName("Tenant guard: another merchant's member cannot read the experiment view")
    void tenantGuard() {
        assertThrows(TenantAccessDeniedException.class, () -> experiments.forMerchant(merchantId, TestUsers.memberOf(UUID.randomUUID())));
    }
}
