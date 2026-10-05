# U7 — Reconciliation recognises recovery only from captured evidence

## CONTEXT
Invariant 6.3. `DefaultPaymentReconciliationService.isSuccessOutcome` treats event types containing `authorized`/`charged` and payload statuses `authorized`/`active` as success. DOC #9: only `captured` is money.

## TASK
Route all status interpretation through `RazorpayEvidenceMapper` (U5) and fix correlation keys to match what U4 now sends.

## FILES TO TOUCH
- `service/DefaultPaymentReconciliationService.java`
- `src/test/java/com/mend/integration/PaymentReconciliationTest.java` (add cases; change existing ones only with a `// M1 behaviour change` comment)

## FILES NOT TO TOUCH
Webhook verification, dedup, outbox, executor, migrations.

## DESIGN
- Success ⇔ payment entity status maps to `CAPTURED` (from `payload.payment.entity.status`). For link events, success ⇔ link status `PAID` **and** embedded payment status `CAPTURED`. Event-type string matching is removed as a success signal (it may still route which parser to use).
- Failure ⇔ payment status `FAILED`.
- `AUTHORIZED_NOT_CAPTURED`, `PENDING`, `REFUNDED`, `UNRECOGNIZED` → `ReconciliationResult.unsupported(...)` (no state change), with INFO log.
- Correlation: also read `notes.mend_intent_id` (what U4 sends); keep reading legacy `notes.action_intent_id` for old data. Stop using `receipt` as an idempotency key fallback (payment links don't send `receipt`; the legacy value was `rcpt_<n>` which can never match).
- Do not change the "late webhook on terminal campaign" behaviour.

## INVARIANTS
Recovery only on captured evidence. Tenant checks untouched.

## FAILURE SEMANTICS
Unrecognised status = no-op + log, never success or failure.

## SECURITY REQUIREMENTS
Webhook payload remains untrusted: amounts/status read defensively; no PII logged.

## TEST REQUIREMENTS
- `payment.authorized` webhook → campaign NOT recovered (regression for the old bug).
- payload status `active` → no change.
- `payment.captured` correlated via `notes.mend_intent_id` → SUCCEEDED + RECOVERED.
- recorded `22_webhook_link_paid.json` (if U0 captured it) → RECOVERED via link correlation.
- intent in EXECUTING (from U6 LinkIssued) + captured webhook → SUCCEEDED + RECOVERED.
- Known gap, document only (M2): a `payment.failed` webhook for the **original** payment can correlate to the latest intent via strategy 4 and mark it FAILED. Add a `@Disabled("M2: correlation of original-payment failures")` test that demonstrates it, so it is tracked.

## ACCEPTANCE CRITERIA
Suite green; no event-type substring decides success.

## DO NOT IMPLEMENT
Polling reconciler, NEEDS_RECONCILIATION, correlation redesign (M2).
