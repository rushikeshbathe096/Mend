# U6 — invoice_key + DB-enforced one in flight per invoice

## TASK
1. `service/InvoiceKeyResolver.java`: from the original failure webhook payment entity: `inv:<invoice_id>` | `ord:<order_id>` | `pay:<payment id>`; validate id shapes (`inv_`, `order_`, `pay_` + alnum); anything else → fallback `cmp:<campaignId>` + WARN. Pure function + tests (doc-derived payloads; label AMBIGUOUS where no recording).
2. Add `campaigns.invoice_key` (new migration **V13**) populated at campaign creation (`CampaignLifecycleService`, where the webhook payload is available); backfill existing rows with `cmp:<id>`.
3. `ActionIntentService.createActionIntentFromCompliance` copies the campaign's `invoice_key` onto the intent. V13 backfills existing intents from their campaign; then `ALTER COLUMN invoice_key SET NOT NULL`.
4. V13: `CREATE UNIQUE INDEX ux_action_intents_one_inflight_per_invoice ON action_intents (merchant_id, invoice_key) WHERE status IN ('CLAIMED','EXECUTING','NEEDS_RECONCILIATION','AWAITING_PAYMENT');` Header comment: runbook query to find violators if the migration fails.
5. Claim path: add `AND NOT EXISTS (in-flight sibling with same merchant_id + invoice_key)` to the claim UPDATE, and perform each claim in its own transaction (or savepoint) so a unique violation under a true race aborts only that intent; catch `DataIntegrityViolationException` → not claimed, audit `CLAIM_BLOCKED_INFLIGHT`.
6. SAFE_RETRY (U5) and any other transition into an in-flight status must also handle the violation → stay put + backoff.
7. Un-disable S7.

## FILES TO TOUCH
New resolver, `Campaign` entity (+field), `CampaignLifecycleService` (creation), `ActionIntentService` (copy), `ActionIntent` (non-null), `ActionIntentTransitions`/claim, V13, tests.

## INVARIANTS
Never edit V12. Fallback derivation must never merge two genuinely different invoices (prefer under-grouping to over-grouping).

## TEST REQUIREMENTS
Resolver table; concurrent claim of two intents on same invoice by two threads → exactly one claimed; index violation path tested directly via SQL; existing campaigns/intents backfilled.

## DO NOT IMPLEMENT
Changing campaign uniqueness `(merchant_id, payment_id)`; merging campaigns.
