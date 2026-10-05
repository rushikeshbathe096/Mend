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
 * Runs the real V12 file against a real pre-V12 schema: a throwaway schema is migrated to V11, seeded with legacy rows,
 * then migrated to V12. The schema is dropped afterwards; the application schema is untouched.
 */
class V12MigrationTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private String schema;

    @BeforeEach
    void setUp() {
        schema = "m2_v12_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        flyway("11").migrate();
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

    private UUID seedIntent(UUID campaignId, String status, String responseReference) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".action_intents (id, campaign_id, attempt_number, action_type, idempotency_key, status, response_reference) "
                + "VALUES (?, ?, 1, 'REQUEST_CUSTOMER_ACTION', ?, ?, ?)", id, campaignId, "key-" + id, status, responseReference);
        return id;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM " + schema + ".action_intents WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("V12 remaps legacy EXECUTING (plink -> AWAITING_PAYMENT, other -> NEEDS_RECONCILIATION) and keeps other rows")
    void remapsLegacyExecutingRows() {
        UUID merchant = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".merchants (id, name) VALUES (?, 'v12 test')", merchant);
        jdbc.update("INSERT INTO " + schema + ".campaigns (id, merchant_id) VALUES (?, ?)", campaign, merchant);
        UUID linkIssued = seedIntent(campaign, "EXECUTING", "plink_V12legacy001");
        UUID unknown = seedIntent(campaign, "EXECUTING", null);
        UUID payRef = seedIntent(campaign, "EXECUTING", "pay_V12legacy002");
        UUID ready = seedIntent(campaign, "READY", null);
        UUID succeeded = seedIntent(campaign, "SUCCEEDED", "plink_V12done00001");

        flyway("12").migrate();

        assertEquals("AWAITING_PAYMENT", statusOf(linkIssued));
        assertEquals("NEEDS_RECONCILIATION", statusOf(unknown));
        assertEquals("NEEDS_RECONCILIATION", statusOf(payRef));
        assertEquals("READY", statusOf(ready));
        assertEquals("SUCCEEDED", statusOf(succeeded));
        assertNotNull(jdbc.queryForObject("SELECT reconcile_after FROM " + schema + ".action_intents WHERE id = ?", Object.class, linkIssued));
        assertNull(jdbc.queryForObject("SELECT reconcile_after FROM " + schema + ".action_intents WHERE id = ?", Object.class, ready));
        assertEquals(0, jdbc.queryForObject("SELECT version FROM " + schema + ".action_intents WHERE id = ?", Long.class, ready));
        assertEquals(0, jdbc.queryForObject("SELECT execution_attempts + reconcile_attempts FROM " + schema + ".action_intents WHERE id = ?", Integer.class, ready));
    }

    @Test
    @DisplayName("V12 CHECK constraints reject unknown statuses and negative attempt counters")
    void checkConstraintsRejectInvalidValues() {
        UUID merchant = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".merchants (id, name) VALUES (?, 'v12 test')", merchant);
        jdbc.update("INSERT INTO " + schema + ".campaigns (id, merchant_id) VALUES (?, ?)", campaign, merchant);
        flyway("12").migrate();

        assertThrows(DataIntegrityViolationException.class, () -> seedIntent(campaign, "BOGUS_STATUS", null));
        UUID ok = seedIntent(campaign, "NEEDS_RECONCILIATION", null);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE " + schema + ".action_intents SET execution_attempts = -1 WHERE id = ?", ok));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = 'idx_action_intents_reconcile_due'",
                Integer.class, schema));
    }
}
