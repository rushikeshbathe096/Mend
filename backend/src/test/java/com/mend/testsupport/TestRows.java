package com.mend.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.UUID;

/**
 * Per-test cleanup of the rows a test created (bug sweep item 6). Test classes share one database per run, so a test
 * removes only its own rows, never a whole table ({@code deleteAll()}).
 *
 * <p>Foreign keys from {@code merchants} cascade to campaigns (and their intents, attempts, classifications, decisions,
 * reviews), merchant config and memberships. Webhook and outbox rows only lose their merchant on delete, so they are
 * removed explicitly. Audit rows are left in place (the audit trail is append-only); their merchant becomes NULL.
 */
public final class TestRows {

    private TestRows() {
    }

    /** Everything a test merchant owns, then the merchant. */
    public static void deleteMerchant(JdbcTemplate jdbc, UUID merchantId) {
        if (merchantId == null) {
            return;
        }
        jdbc.update("DELETE FROM outbox_events WHERE merchant_id = ?", merchantId);
        jdbc.update("DELETE FROM webhook_events WHERE merchant_id = ?", merchantId);
        jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId);
    }

    /** Webhook events by external event id (their outbox rows and classifications cascade). */
    public static void deleteWebhookEvents(JdbcTemplate jdbc, Collection<String> externalEventIds) {
        for (String externalEventId : externalEventIds) {
            jdbc.update("DELETE FROM webhook_events WHERE external_event_id = ?", externalEventId);
        }
    }

    /** Webhook events by id (their outbox rows and classifications cascade). */
    public static void deleteWebhookEventsById(JdbcTemplate jdbc, Collection<UUID> ids) {
        for (UUID id : ids) {
            jdbc.update("DELETE FROM webhook_events WHERE id = ?", id);
        }
    }

    /** Webhook events with this external event id (0 or more). */
    public static long countWebhookEvents(JdbcTemplate jdbc, String externalEventId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE external_event_id = ?", Long.class, externalEventId);
        return count == null ? 0 : count;
    }

    /** Outbox rows for the webhook events with this external event id. */
    public static long countOutboxForWebhook(JdbcTemplate jdbc, String externalEventId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events o JOIN webhook_events w ON o.aggregate_id = w.id "
                + "WHERE w.external_event_id = ?", Long.class, externalEventId);
        return count == null ? 0 : count;
    }

    /** Action intents by id (nothing references them by FK). */
    public static void deleteIntents(JdbcTemplate jdbc, Collection<UUID> intentIds) {
        for (UUID id : intentIds) {
            jdbc.update("DELETE FROM action_intents WHERE id = ?", id);
        }
    }
}
