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

/**
 * Runs the real V13 file against a real V12 schema in a throwaway schema: backfills (campaign and intent invoice keys,
 * legacy dispatch markers), NOT NULL, and the one-in-flight-per-invoice index, including the failure on existing duplicates.
 */
class V13MigrationTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private String schema;
    private UUID merchant;

    @BeforeEach
    void setUp() {
        schema = "m2_v13_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        flyway("12").migrate();
        merchant = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".merchants (id, name) VALUES (?, 'v13 test')", merchant);
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

    private UUID seedCampaign() {
        UUID campaign = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".campaigns (id, merchant_id) VALUES (?, ?)", campaign, merchant);
        return campaign;
    }

    /** Before V13 the key is left NULL (to be backfilled); after V13 it is the campaign's key, as the application writes it. */
    private UUID seedIntent(UUID campaignId, String status) {
        return seedIntent(campaignId, status, null);
    }

    private UUID seedIntent(UUID campaignId, String status, String invoiceKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".action_intents (id, merchant_id, campaign_id, attempt_number, action_type, idempotency_key, status, claimed_at, invoice_key) "
                + "VALUES (?, ?, ?, 1, 'REQUEST_CUSTOMER_ACTION', ?, ?, now() - interval '1 hour', ?)", id, merchant, campaignId, "key-" + id, status, invoiceKey);
        return id;
    }

    private String column(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM " + schema + "." + table + " WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("V13 backfills campaign and intent invoice keys (cmp:<campaign>), legacy dispatch markers, and creates the index")
    void backfillsAndCreatesTheIndex() {
        UUID campaign = seedCampaign();
        UUID legacyReconcile = seedIntent(campaign, "NEEDS_RECONCILIATION");
        UUID done = seedIntent(campaign, "SUCCEEDED");

        flyway("13").migrate();

        assertEquals("cmp:" + campaign, column("campaigns", "invoice_key", campaign));
        assertEquals("cmp:" + campaign, column("action_intents", "invoice_key", legacyReconcile));
        assertEquals("cmp:" + campaign, column("action_intents", "invoice_key", done));
        assertNotNull(column("action_intents", "dispatched_at", legacyReconcile), "legacy reconcile row gets a dispatch marker");
        assertEquals("1", column("action_intents", "execution_attempts", legacyReconcile));
        assertNull(column("action_intents", "dispatched_at", done), "non-reconcile rows untouched");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = 'ux_action_intents_one_inflight_per_invoice'",
                Integer.class, schema));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE " + schema + ".action_intents SET invoice_key = NULL WHERE id = ?", done));
    }

    @Test
    @DisplayName("After V13 a second in-flight intent for the same (merchant, invoice_key) is rejected by the database")
    void indexRejectsASecondInFlightIntent() {
        UUID campaign = seedCampaign();
        flyway("13").migrate();
        String key = "cmp:" + campaign;
        seedIntent(campaign, "AWAITING_PAYMENT", key);
        seedIntent(campaign, "READY", key);
        seedIntent(campaign, "FAILED", key);

        assertThrows(DataIntegrityViolationException.class, () -> seedIntent(campaign, "CLAIMED", key));
        seedIntent(campaign, "CLAIMED", "cmp:" + UUID.randomUUID());   // another invoice is fine
    }

    @Test
    @DisplayName("V13 fails (runbook case) when existing data already has two in-flight intents for one campaign")
    void failsOnExistingDuplicates() {
        UUID campaign = seedCampaign();
        seedIntent(campaign, "EXECUTING");
        seedIntent(campaign, "NEEDS_RECONCILIATION");

        assertThrows(RuntimeException.class, () -> flyway("13").migrate());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = 'ux_action_intents_one_inflight_per_invoice'",
                Integer.class, schema));
    }
}
