# U2 — Schema V12, new states, entity mapping

## TASK
One Flyway migration + enum + entity fields. No behaviour change in services yet.

## FILES TO TOUCH
- `backend/src/main/resources/db/migration/V12__execution_safety.sql`
- `domain/enums/ActionIntentStatus.java`: add `NEEDS_RECONCILIATION`, `AWAITING_PAYMENT` (both non-terminal, non-executable); add `isInFlight()` = CLAIMED, EXECUTING, NEEDS_RECONCILIATION, AWAITING_PAYMENT.
- `domain/entity/ActionIntent.java`: fields `dispatchedAt`, `reconcileAfter`, `reconcileAttempts` (int, default 0), `executionAttempts` (int, default 0), `invoiceKey` (nullable until U6). **Do not add `@Version` yet (U4).**
- `ActionIntentStateMachineTest` (extend), new `V12MigrationTest`.

## FILES NOT TO TOUCH
Services, controllers, scheduler, reconciliation.

## DESIGN — V12 contents
```sql
-- Header comment: purpose, ADR-002, and RUNBOOK for failures (how to inspect offending rows).
ALTER TABLE action_intents
  ADD COLUMN dispatched_at       TIMESTAMPTZ,
  ADD COLUMN reconcile_after     TIMESTAMPTZ,
  ADD COLUMN reconcile_attempts  INT NOT NULL DEFAULT 0,
  ADD COLUMN execution_attempts  INT NOT NULL DEFAULT 0,
  ADD COLUMN invoice_key         VARCHAR(120),
  ADD COLUMN version             BIGINT NOT NULL DEFAULT 0;

-- legacy remap (M1 left these in EXECUTING)
UPDATE action_intents SET status = 'AWAITING_PAYMENT', reconcile_after = now()
 WHERE status = 'EXECUTING' AND response_reference LIKE 'plink\_%' ESCAPE '\';
UPDATE action_intents SET status = 'NEEDS_RECONCILIATION', reconcile_after = now()
 WHERE status = 'EXECUTING';

ALTER TABLE action_intents ADD CONSTRAINT ck_action_intents_status CHECK (status IN
 ('PENDING','SCHEDULED','READY','CLAIMED','PROCESSING','EXECUTING','NEEDS_RECONCILIATION',
  'AWAITING_PAYMENT','SUCCEEDED','FAILED','CANCELLED','EXPIRED'));
ALTER TABLE action_intents ADD CONSTRAINT ck_action_intents_attempts
  CHECK (reconcile_attempts >= 0 AND execution_attempts >= 0);

CREATE INDEX idx_action_intents_reconcile_due ON action_intents (reconcile_after)
  WHERE status IN ('EXECUTING','NEEDS_RECONCILIATION','AWAITING_PAYMENT');
```
Use whatever timestamp type the existing columns use (check V1/V8: `TIMESTAMP` vs `TIMESTAMP WITH TIME ZONE`) and stay consistent with JPA mapping under `ddl-auto=validate`. The `version` column is added now (DEFAULT 0) so U4 only touches Java.
The one-in-flight unique index is **U6**, not here.

## INVARIANTS
`ddl-auto=validate` must pass. Existing data preserved. Never edit V1–V11.

## TEST REQUIREMENTS
- Migration test: seed EXECUTING rows (with and without `plink_` reference) on a pre-V12 schema if the harness allows (Flyway `target`), else assert remap logic via SQL on a fresh schema; CHECK rejects an unknown status.
- Enum predicates for the new states.

## ACCEPTANCE CRITERIA
Suite green, count ≥ previous; context starts with validate.

## DO NOT IMPLEMENT
Transitions, reconciler, @Version, unique index.
