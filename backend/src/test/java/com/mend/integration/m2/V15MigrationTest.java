package com.mend.integration.m2;

import com.mend.integration.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real V15 file against a real V14 schema in a throwaway schema: provider_payment_id backfill and the new checks. */
class V15MigrationTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private String schema;
    private UUID merchant;

    @BeforeEach
    void setUp() {
        schema = "m2_v15_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        flyway("14").migrate();
        merchant = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".merchants (id, name) VALUES (?, 'v15 test')", merchant);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private UUID seedCampaign(String paymentId) {
        UUID campaign = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".campaigns (id, merchant_id, payment_id, invoice_key) VALUES (?, ?, ?, ?)",
                campaign, merchant, paymentId, "cmp:" + campaign);
        return campaign;
    }

    private String providerPaymentId(UUID campaign) {
        return jdbc.queryForObject("SELECT provider_payment_id FROM " + schema + ".campaigns WHERE id = ?", String.class, campaign);
    }

    @Test
    @DisplayName("V15 backfills provider_payment_id only from a raw pay_ id or the composite payment.<event>_pay_<id>")
    void backfillsOnlyCertainIds() {
        UUID raw = seedCampaign("pay_RawAbc123");
        UUID composite = seedCampaign("payment.failed_pay_CompXyz789");
        UUID evt = seedCampaign("evt_demo_duplicate_1");
        UUID unknown = seedCampaign("UNKNOWN_PAYMENT_" + UUID.randomUUID());
        UUID hostile = seedCampaign("payment.failed_pay_x'; DROP TABLE campaigns; --");
        UUID otherEntity = seedCampaign("subscription.halted_sub_Abc");

        flyway("15").migrate();

        assertEquals("pay_RawAbc123", providerPaymentId(raw));
        assertEquals("pay_CompXyz789", providerPaymentId(composite));
        assertNull(providerPaymentId(evt));
        assertNull(providerPaymentId(unknown));
        assertNull(providerPaymentId(hostile));
        assertNull(providerPaymentId(otherEntity));
        assertEquals("payment.failed_pay_CompXyz789",
                jdbc.queryForObject("SELECT payment_id FROM " + schema + ".campaigns WHERE id = ?", String.class, composite),
                "payment_id is not changed");
        assertNull(jdbc.queryForObject("SELECT recovery_attribution FROM " + schema + ".campaigns WHERE id = ?", String.class, raw));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = 'ix_campaigns_merchant_provider_payment_id'",
                Integer.class, schema));
    }

    @Test
    @DisplayName("After V15 the database rejects an unknown attribution and a malformed provider_payment_id")
    void checksRejectBadValues() {
        UUID campaign = seedCampaign("pay_Check1");
        flyway("15").migrate();

        jdbc.update("UPDATE " + schema + ".campaigns SET recovery_attribution = 'ORIGINAL_PAYMENT_LATE_CAPTURE' WHERE id = ?", campaign);
        jdbc.update("UPDATE " + schema + ".campaigns SET recovery_attribution = 'MEND' WHERE id = ?", campaign);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE " + schema + ".campaigns SET recovery_attribution = 'AI' WHERE id = ?", campaign));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE " + schema + ".campaigns SET provider_payment_id = 'payment.failed_pay_x' WHERE id = ?", campaign));
        UUID second = seedCampaign("pay_Check2");
        jdbc.update("UPDATE " + schema + ".campaigns SET provider_payment_id = 'pay_Check1' WHERE id = ?", second);   // not unique, by design
    }
}
