-- M2 U6 review: an intent's invoice_key must equal its campaign's invoice_key, enforced by the database. Without this,
-- an intent created with a different key (e.g. the cmp:<campaign> default while the campaign holds ord:<order>) would
-- slip past ux_action_intents_one_inflight_per_invoice (AGENTS.md invariant 14). V13 is not edited (invariant 10).
--
-- Locking: ADD CONSTRAINT UNIQUE builds an index (SHARE lock on campaigns); the FOREIGN KEY validation scans
-- action_intents under SHARE ROW EXCLUSIVE on both tables. Both are small in M2.
--
-- RUNBOOK if the FOREIGN KEY fails to validate (intents whose key differs from their campaign's):
--   SELECT a.id, a.status, a.invoice_key AS intent_key, c.invoice_key AS campaign_key
--     FROM action_intents a JOIN campaigns c ON c.id = a.campaign_id
--    WHERE a.invoice_key <> c.invoice_key;
-- Decide per row from provider evidence; do not blindly copy the campaign key onto in-flight rows (that can create
-- duplicates that V13's index would then reject).
--
-- Correction to V13's header runbook (V13 is never edited): after a failed V13 the invoice_key columns are rolled
-- back (NULL / absent), so group by the campaign instead:
--   SELECT merchant_id, campaign_id, array_agg(id || ':' || status ORDER BY created_at)
--     FROM action_intents WHERE status IN ('CLAIMED','EXECUTING','NEEDS_RECONCILIATION','AWAITING_PAYMENT')
--    GROUP BY merchant_id, campaign_id HAVING count(*) > 1;
-- V13's lock note is also incomplete: ADD COLUMN / SET NOT NULL take ACCESS EXCLUSIVE locks (reads blocked too) until commit.

ALTER TABLE campaigns ADD CONSTRAINT uq_campaigns_id_invoice_key UNIQUE (id, invoice_key);
ALTER TABLE action_intents ADD CONSTRAINT fk_action_intents_campaign_invoice_key
    FOREIGN KEY (campaign_id, invoice_key) REFERENCES campaigns (id, invoice_key);
