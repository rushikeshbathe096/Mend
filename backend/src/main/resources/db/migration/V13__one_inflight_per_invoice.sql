-- M2 U6: invoice identity + at most one in-flight recovery intent per (merchant, invoice_key), enforced by the database.
-- See docs/m2/M2-execution-safety.md §6 and AGENTS.md invariant 14. Never edit V12; this migration only adds.
--
-- Locking: plain CREATE UNIQUE INDEX (Flyway runs it inside the migration transaction) holds a SHARE lock on
-- action_intents for the build, blocking writes (not reads) for its duration. action_intents is small in M2; for a large
-- table, build the index CONCURRENTLY out of band first and make this migration a no-op check.
--
-- RUNBOOK if this migration fails on ux_action_intents_one_inflight_per_invoice (existing duplicates):
--   SELECT merchant_id, invoice_key, array_agg(id || ':' || status ORDER BY created_at) AS intents
--     FROM action_intents
--    WHERE status IN ('CLAIMED','EXECUTING','NEEDS_RECONCILIATION','AWAITING_PAYMENT')
--    GROUP BY merchant_id, invoice_key
--   HAVING count(*) > 1;
-- Resolve each group by hand from provider evidence (never by guessing which one "won"), then re-run the migration.

-- 1. campaigns.invoice_key; existing campaigns get the per-campaign fallback (no webhook re-parsing in SQL).
ALTER TABLE campaigns ADD COLUMN invoice_key VARCHAR(120);
UPDATE campaigns SET invoice_key = 'cmp:' || id WHERE invoice_key IS NULL;
ALTER TABLE campaigns ALTER COLUMN invoice_key SET NOT NULL;

-- 2. action_intents.invoice_key (column added in V12) copied from the campaign; NOT NULL from now on.
UPDATE action_intents a SET invoice_key = c.invoice_key
  FROM campaigns c
 WHERE c.id = a.campaign_id AND a.invoice_key IS NULL;
UPDATE action_intents SET invoice_key = 'int:' || id WHERE invoice_key IS NULL;  -- intents without a campaign row
ALTER TABLE action_intents ALTER COLUMN invoice_key SET NOT NULL;

-- 3. Legacy reconcile rows remapped by V12 have no dispatch marker (U2 review): they were dispatched at an unknown time.
UPDATE action_intents
   SET dispatched_at = COALESCE(claimed_at, now()),
       execution_attempts = GREATEST(execution_attempts, 1)
 WHERE status IN ('NEEDS_RECONCILIATION', 'AWAITING_PAYMENT')
   AND dispatched_at IS NULL;

-- 4. The invariant.
CREATE UNIQUE INDEX ux_action_intents_one_inflight_per_invoice
    ON action_intents (merchant_id, invoice_key)
 WHERE status IN ('CLAIMED', 'EXECUTING', 'NEEDS_RECONCILIATION', 'AWAITING_PAYMENT');
