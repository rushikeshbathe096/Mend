-- V12 — M2 execution safety (ADR-002, docs/m2/M2-execution-safety.md §4–§7).
--
-- Adds the columns the fenced state machine needs (dispatch marker, reconciliation scheduling, attempt counters,
-- invoice key placeholder, optimistic-lock version), remaps intents that M1 left in EXECUTING to the new
-- non-terminal states, and constrains status values.
--
-- RUNBOOK if this migration fails:
--   * CHECK ck_action_intents_status violated → an intent has a status outside the known set. Inspect with:
--       SELECT status, count(*) FROM action_intents GROUP BY status ORDER BY 1;
--     Map unknown values to a valid status deliberately (with an audit note); never edit this file.
--   * CHECK ck_action_intents_attempts cannot fail on fresh columns (defaults are 0).
--   Then re-run the application so Flyway retries V12.
-- The one-in-flight-per-invoice unique index is V13 (U6), not here.

ALTER TABLE action_intents
    ADD COLUMN dispatched_at      TIMESTAMP WITH TIME ZONE,
    ADD COLUMN reconcile_after    TIMESTAMP WITH TIME ZONE,
    ADD COLUMN reconcile_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN execution_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN invoice_key        VARCHAR(120),
    ADD COLUMN version            BIGINT NOT NULL DEFAULT 0;

-- Legacy remap: M1 left LinkIssued and OutcomeUnknown intents in EXECUTING (non-terminal, never resolved).
UPDATE action_intents
   SET status = 'AWAITING_PAYMENT', reconcile_after = now()
 WHERE status = 'EXECUTING' AND response_reference LIKE 'plink\_%' ESCAPE '\';

UPDATE action_intents
   SET status = 'NEEDS_RECONCILIATION', reconcile_after = now()
 WHERE status = 'EXECUTING';

ALTER TABLE action_intents ADD CONSTRAINT ck_action_intents_status CHECK (status IN
    ('PENDING', 'SCHEDULED', 'READY', 'CLAIMED', 'PROCESSING', 'EXECUTING', 'NEEDS_RECONCILIATION',
     'AWAITING_PAYMENT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'));

ALTER TABLE action_intents ADD CONSTRAINT ck_action_intents_attempts
    CHECK (reconcile_attempts >= 0 AND execution_attempts >= 0);

CREATE INDEX idx_action_intents_reconcile_due ON action_intents (reconcile_after)
    WHERE status IN ('EXECUTING', 'NEEDS_RECONCILIATION', 'AWAITING_PAYMENT');
