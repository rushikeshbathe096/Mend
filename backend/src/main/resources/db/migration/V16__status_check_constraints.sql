-- V16 — cleanup Group 6 (docs/audit/cleanup-report.md): the database now enforces the same status sets as the Java enums.
--
-- Adds CHECK constraints to the three status columns that had none (campaigns.current_state, review_queue.status,
-- webhook_events.processing_status) and replaces ck_action_intents_status (V12) without PROCESSING, which no code ever
-- wrote. The legacy enum values removed in the same commit can no longer be stored.
--
-- This migration never rewrites data. If any row holds a value outside the allowed sets, the DO block raises before any
-- constraint changes and the whole migration rolls back.
--
-- RUNBOOK if this migration fails with "V16: legacy status values present":
--   1. List the offending rows:
--        SELECT 'campaigns.current_state' AS col, current_state AS value, count(*) FROM campaigns
--         WHERE current_state NOT IN ('CREATED','CLASSIFIED','ELIGIBLE','SCHEDULED','ACTION_PENDING','EXECUTING',
--                                     'RECOVERED','EXHAUSTED','FAILED','CANCELLED') GROUP BY 2
--        UNION ALL
--        SELECT 'action_intents.status', status, count(*) FROM action_intents
--         WHERE status NOT IN ('PENDING','SCHEDULED','READY','CLAIMED','EXECUTING','NEEDS_RECONCILIATION',
--                              'AWAITING_PAYMENT','SUCCEEDED','FAILED','CANCELLED','EXPIRED') GROUP BY 2
--        UNION ALL
--        SELECT 'review_queue.status', status, count(*) FROM review_queue
--         WHERE status NOT IN ('PENDING','APPROVED','REJECTED','CLOSED','ESCALATED') GROUP BY 2
--        UNION ALL
--        SELECT 'webhook_events.processing_status', processing_status, count(*) FROM webhook_events
--         WHERE processing_status NOT IN ('RECEIVED','VERIFIED','PROCESSED','FAILED') GROUP BY 2;
--   2. Decide each row deliberately (move it to a valid status with an audit note, or stay on the previous
--      application version until it is resolved). Never edit this file.
--   3. Restart the application; Flyway retries V16.

DO $$
DECLARE
    legacy text;
BEGIN
    SELECT string_agg(col || '=' || value || ' (' || n || ')', ', ' ORDER BY col, value) INTO legacy FROM (
        SELECT 'campaigns.current_state' AS col, current_state AS value, count(*) AS n FROM campaigns
         WHERE current_state NOT IN ('CREATED', 'CLASSIFIED', 'ELIGIBLE', 'SCHEDULED', 'ACTION_PENDING', 'EXECUTING',
                                     'RECOVERED', 'EXHAUSTED', 'FAILED', 'CANCELLED') GROUP BY 2
        UNION ALL
        SELECT 'action_intents.status', status, count(*) FROM action_intents
         WHERE status NOT IN ('PENDING', 'SCHEDULED', 'READY', 'CLAIMED', 'EXECUTING', 'NEEDS_RECONCILIATION',
                              'AWAITING_PAYMENT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED') GROUP BY 2
        UNION ALL
        SELECT 'review_queue.status', status, count(*) FROM review_queue
         WHERE status NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'CLOSED', 'ESCALATED') GROUP BY 2
        UNION ALL
        SELECT 'webhook_events.processing_status', processing_status, count(*) FROM webhook_events
         WHERE processing_status NOT IN ('RECEIVED', 'VERIFIED', 'PROCESSED', 'FAILED') GROUP BY 2
    ) t;
    IF legacy IS NOT NULL THEN
        RAISE EXCEPTION 'V16: legacy status values present, no data was changed: %. See the runbook in V16__status_check_constraints.sql.', legacy;
    END IF;
END $$;

ALTER TABLE campaigns ADD CONSTRAINT ck_campaigns_current_state CHECK (current_state IN
    ('CREATED', 'CLASSIFIED', 'ELIGIBLE', 'SCHEDULED', 'ACTION_PENDING', 'EXECUTING',
     'RECOVERED', 'EXHAUSTED', 'FAILED', 'CANCELLED'));

ALTER TABLE action_intents DROP CONSTRAINT ck_action_intents_status;
ALTER TABLE action_intents ADD CONSTRAINT ck_action_intents_status CHECK (status IN
    ('PENDING', 'SCHEDULED', 'READY', 'CLAIMED', 'EXECUTING', 'NEEDS_RECONCILIATION',
     'AWAITING_PAYMENT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'));

ALTER TABLE review_queue ADD CONSTRAINT ck_review_queue_status CHECK (status IN
    ('PENDING', 'APPROVED', 'REJECTED', 'CLOSED', 'ESCALATED'));

ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_processing_status CHECK (processing_status IN
    ('RECEIVED', 'VERIFIED', 'PROCESSED', 'FAILED'));
