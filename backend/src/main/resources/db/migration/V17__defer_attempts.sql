-- V17__defer_attempts.sql
-- Bug sweep item 4: MEND-side deferrals get their own counter. Before, deferrals and SAFE_RETRY shared execution_attempts
-- (cap 5 vs 3), so e.g. 2 deferrals + an Unknown outcome left no SAFE_RETRY (ADR-002 deviation).
--
-- From now on a deferral moves its dispatch from execution_attempts to defer_attempts in the same fenced update, so
-- execution_attempts counts only dispatches that were not deferred (the SAFE_RETRY budget) and
-- execution_attempts + defer_attempts is still the total number of dispatches.
--
-- Additive only: existing rows get defer_attempts = 0 and keep their execution_attempts unchanged (no data is rewritten).
-- An intent deferred before V17 therefore keeps counting those deferrals against SAFE_RETRY: the old, stricter budget.
-- ADD COLUMN with a constant default is metadata-only on PostgreSQL 11+.
ALTER TABLE action_intents ADD COLUMN defer_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE action_intents ADD CONSTRAINT ck_action_intents_defer_attempts CHECK (defer_attempts >= 0);
