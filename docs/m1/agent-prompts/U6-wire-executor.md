# U6 — Wire ActionExecutionService to PaymentProvider; remove invented endpoints

**This unit changes production behaviour. Read the whole prompt before editing.**

## CONTEXT
`AGENTS.md`; `M1-design-doc.md` "Architecture → Executor mapping" table is the spec; `M1-provider-correctness.md` §9. U1–U5 merged.

## CURRENT IMPLEMENTATION
`ActionExecutionService.executeActionIntent`: prepare (tx) → `paymentProviderClient.executeAction` (no tx) → `finalizeExecution` (tx) mapping SUCCESS→SUCCEEDED+RECOVERED, FAILURE→FAILED+EXHAUSTED, ERROR→FAILED. `MockPaymentProviderClient` is the default bean.

## TASK
1. Introduce `service/ProviderActionDispatcher.java`: translates an `ActionIntent` + `Campaign` into a provider call and returns a MEND-level `ProviderActionOutcome` (sealed: `LinkIssued(snapshot)`, `Rejected(kind, code, reason)`, `NotSent(cause)`, `Unknown(cause)`, `NotSupported(reason)`). Owns: attempt-key derivation, amount resolution (U3), choosing the operation by `ActionType`.
2. `ActionExecutionService` calls the dispatcher instead of `PaymentProviderClient`; `finalizeExecution` maps outcomes per the design-doc table. Keep the three-phase structure and all compliance/tenant/expiry checks byte-for-byte.
3. `MockPaymentProvider implements PaymentProvider` (`@ConditionalOnProperty(... havingValue="mock", matchIfMissing=true)`), deterministic, ids prefixed `plink_mock_` / `pay_mock_`, log marker `SYNTHETIC`. Preserve whatever simulation knobs `MockPaymentProviderClient` exposes that existing tests use (read its tests first).
4. Delete `client/PaymentProviderClient.java`, `client/RazorpayPaymentProviderClient.java`, `client/MockPaymentProviderClient.java`, `dto/payment/*` **only after** all references are gone.
5. Rewrite (do not delete) legacy tests that assert invented endpoints; see TEST REQUIREMENTS.

## FILES TO TOUCH
`service/ActionExecutionService.java`, new `service/ProviderActionDispatcher.java`, new `provider/mock/MockPaymentProvider.java`, `domain/enums/AuditEventType.java` (add `PROVIDER_REQUEST_ACCEPTED, PROVIDER_REQUEST_REJECTED, PROVIDER_REQUEST_NOT_SENT, PROVIDER_OUTCOME_UNKNOWN` if audit types are enum-checked), the three deleted client files + dto/payment, `CampaignController` (only the type of the returned result if it exposes `PaymentExecutionResult`), and tests: `client/RazorpayPaymentProviderClientTest`, `client/MockPaymentProviderClientTest`, `client/PaymentProviderClientIntegrationTest`, `client/RazorpayProviderSelectionTest`, `integration/Phase20_RazorpayTestModeE2EValidationTest`, `integration/RazorpayTestModeEndToEndIntegrationTest`, `service/ActionExecutionServiceTest`, and any other test that fails to compile because of the removed types.

## FILES NOT TO TOUCH
`ActionIntentClaimService` (M2), `ActionScheduler` claim/lease logic (M2), migrations, reconciliation (U7), AI code, compliance logic.

## DESIGN
Dispatcher:
- `REQUEST_CUSTOMER_ACTION` → amount via `WebhookAmountResolver.resolveOriginalAmount(merchantId, campaign.paymentId)`; empty → `Rejected(LOCAL_PRECONDITION, "AMOUNT_UNRESOLVED")`. Key = `ProviderAttemptKey.forIntent(intent.id)`. Description: `"Payment for <merchant-visible ref>"` — no PII. Call `createPaymentLink`.
- `RETRY_PAYMENT` in **razorpay mode** → `NotSupported("RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2")`, **no provider call**. In **mock mode** keep the existing mock behaviour so unrelated E2E tests (Phase19_5 etc.) still exercise the flow; label those results SYNTHETIC. Implement the switch via a `ProviderCapabilities` bean (`supportsRecurringDebit()`): mock = true, razorpay = false. Audit event types are free-form strings passed to `AuditService.logEvent` (the `AuditEventType` enum is not used there) — use the string names below, and also add them to the enum for documentation.
- `MANUAL_REVIEW` → unchanged existing behaviour (read the current code path; do not alter it).
finalizeExecution mapping:
| Outcome | intent.status | intent.responseReference | campaign | audit |
|---|---|---|---|---|
| LinkIssued | EXECUTING | plink id | unchanged (EXECUTING) | PROVIDER_REQUEST_ACCEPTED |
| Rejected / NotSupported / NotSent | FAILED, completedAt set | null | existing FAILURE path (FAILED→EXHAUSTED) | PROVIDER_REQUEST_REJECTED / _NOT_SENT |
| Unknown | EXECUTING | null | unchanged | PROVIDER_OUTCOME_UNKNOWN |
| (mock) synthetic captured success | existing SUCCEEDED + RECOVERED path, audit detail contains `SYNTHETIC` | | | |
`campaign_attempts` row: status `ISSUED` (LinkIssued), `FAILED`, `UNKNOWN`. Column is VARCHAR(20): fits.
**No code path may set RECOVERED from a Razorpay request outcome.**

## INVARIANTS
Compliance re-check still runs in prepare (6.4). Tenant check unchanged. One audit row per finalize. No new state enum values (M2 adds NEEDS_RECONCILIATION).

## FAILURE SEMANTICS
Unknown → EXECUTING is deliberate: `releaseExpiredClaims` only selects CLAIMED, so the intent is not recycled. Write a test proving an EXECUTING intent is not returned by `findExpiredClaims`.

## SECURITY REQUIREMENTS
Audit details contain ids/codes only; no PII; no raw provider bodies.

## TEST REQUIREMENTS
Behaviour-change rewrites (put a comment `// M1 behaviour change: <reason>, see ADR-001` above each changed assertion):
- Phase20 / RazorpayTestModeE2E: replace `/v1/payments/{id}/retry` expectations with (a) RETRY_PAYMENT in razorpay mode → zero HTTP calls, intent FAILED, audit PROVIDER_REQUEST_REJECTED reason RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2; (b) REQUEST_CUSTOMER_ACTION against recorded fixture `01` → intent EXECUTING, campaign **not** RECOVERED.
- RazorpayPaymentProviderClientTest → becomes/moves to coverage already in U4 contract tests; keep test count by converting cases (timeout, 5xx, decline, unsupported, idempotency propagation → "attempt key propagated as reference_id").
New: `ActionExecutionServiceProviderOutcomeTest` — one test per mapping row; Unknown leaves intent EXECUTING and campaign EXECUTING; `findExpiredClaims` ignores EXECUTING.
Grep gate: `grep -rn "payments/.*retry\|subscriptions/.*charge\|X-Razorpay-Idempotency-Header\|X-Idempotency-Key" backend/src` → no matches.

## ACCEPTANCE CRITERIA
Suite green; total test count ≥ U0 baseline; grep gate clean; no `PaymentProviderClient` references remain.

## DO NOT IMPLEMENT
Reconciliation job, NEEDS_RECONCILIATION state, fencing, lease changes, recurring debit, customer notifications, metrics registration.

## REVIEW CHECKLIST
Diff `prepareExecution` — must be unchanged except for types. Search for `RECOVERED` in the diff. Confirm mock mode tests still exercise E2E flows and are labelled SYNTHETIC.
