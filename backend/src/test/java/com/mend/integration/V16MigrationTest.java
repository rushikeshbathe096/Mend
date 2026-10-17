package com.mend.integration;

import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ReviewQueueStatus;
import com.mend.domain.enums.WebhookEventStatus;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
 * Cleanup Group 6: runs the real V16 file against a real V15 schema in a throwaway schema. V16 must fail without touching
 * data when a legacy status is stored, and afterwards the database must accept exactly the Java enum values.
 */
class V16MigrationTest extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private String schema;
    private UUID merchant;
    private UUID campaign;
    private UUID intent;
    private UUID review;
    private UUID webhook;

    @BeforeEach
    void setUp() {
        schema = "cleanup_v16_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        flyway("15").migrate();
        merchant = UUID.randomUUID();
        campaign = UUID.randomUUID();
        intent = UUID.randomUUID();
        review = UUID.randomUUID();
        webhook = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".merchants (id, name) VALUES (?, 'v16 test')", merchant);
        jdbc.update("INSERT INTO " + schema + ".campaigns (id, merchant_id, invoice_key) VALUES (?, ?, ?)", campaign, merchant, "cmp:" + campaign);
        jdbc.update("INSERT INTO " + schema + ".action_intents (id, merchant_id, campaign_id, attempt_number, action_type, idempotency_key, invoice_key) "
                + "VALUES (?, ?, ?, 1, 'RETRY_PAYMENT', ?, ?)", intent, merchant, campaign, "v16:" + intent, "cmp:" + campaign);
        jdbc.update("INSERT INTO " + schema + ".review_queue (id, campaign_id, merchant_id) VALUES (?, ?, ?)", review, campaign, merchant);
        jdbc.update("INSERT INTO " + schema + ".webhook_events (id, external_event_id, event_type) VALUES (?, ?, 'payment.failed')", webhook, "evt_v16_" + webhook);
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

    private void set(String table, String column, UUID id, String value) {
        jdbc.update("UPDATE " + schema + "." + table + " SET " + column + " = ? WHERE id = ?", value, id);
    }

    private String get(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + schema + "." + table + " WHERE id = ?", String.class, id);
    }

    private int constraintCount(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace "
                + "WHERE n.nspname = ? AND c.conname = ?", Integer.class, schema, name);
    }

    @Test
    @DisplayName("V16 fails, and changes no data, when a legacy status value is stored")
    void legacyRowsMakeV16FailWithoutRewritingData() {
        set("campaigns", "current_state", campaign, "PENDING_REVIEW");
        set("action_intents", "status", intent, "PROCESSING");   // allowed by V12's CHECK
        set("review_queue", "status", review, "IN_REVIEW");
        set("webhook_events", "processing_status", webhook, "IGNORED");

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway("16").migrate());
        assertTrue(String.valueOf(failure.getMessage()).contains("V16: legacy status values present")
                        || String.valueOf(failure.getCause()).contains("V16: legacy status values present"),
                "the failure names the cause: " + failure.getMessage());

        assertEquals("PENDING_REVIEW", get("campaigns", "current_state", campaign), "data is not rewritten");
        assertEquals("PROCESSING", get("action_intents", "status", intent));
        assertEquals("IN_REVIEW", get("review_queue", "status", review));
        assertEquals("IGNORED", get("webhook_events", "processing_status", webhook));
        assertEquals(0, constraintCount("ck_campaigns_current_state"), "V16 rolled back as a whole");
        assertEquals(1, constraintCount("ck_action_intents_status"), "the V12 constraint is still in place");
        assertEquals("15", flyway("16").info().current().getVersion().getVersion());
    }

    @Test
    @DisplayName("After V16 the database accepts exactly the enum values and rejects the removed legacy values")
    void constraintsMatchTheEnums() {
        flyway("16").migrate();

        for (CampaignStatus status : CampaignStatus.values()) {
            set("campaigns", "current_state", campaign, status.name());
        }
        for (ActionIntentStatus status : ActionIntentStatus.values()) {
            set("action_intents", "status", intent, status.name());
        }
        for (ReviewQueueStatus status : ReviewQueueStatus.values()) {
            set("review_queue", "status", review, status.name());
        }
        for (WebhookEventStatus status : WebhookEventStatus.values()) {
            set("webhook_events", "processing_status", webhook, status.name());
        }

        for (String legacy : new String[]{"STRATEGY_ASSIGNED", "WAITING", "ACTION_EXECUTING", "RETRY_SCHEDULED", "PENDING_REVIEW",
                "ESCALATED", "MANDATE_INVALID", "STOPPED"}) {
            assertThrows(DataIntegrityViolationException.class, () -> set("campaigns", "current_state", campaign, legacy), legacy);
        }
        assertThrows(DataIntegrityViolationException.class, () -> set("action_intents", "status", intent, "PROCESSING"));
        for (String legacy : new String[]{"IN_REVIEW", "OVERRIDDEN"}) {
            assertThrows(DataIntegrityViolationException.class, () -> set("review_queue", "status", review, legacy), legacy);
        }
        for (String legacy : new String[]{"PROCESSING", "IGNORED", "INVALID_SIGNATURE"}) {
            assertThrows(DataIntegrityViolationException.class, () -> set("webhook_events", "processing_status", webhook, legacy), legacy);
        }
    }
}
