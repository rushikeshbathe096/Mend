# M1 summary — provider correctness (closed 2026-10-05)

1. MEND now talks to Razorpay only through `com.mend.provider.PaymentProvider`, using only the §6 endpoints; the invented `/payments/{id}/retry`, `/payments/retry` and `/subscriptions/{id}/charge` calls and the undocumented idempotency headers are gone.
2. Every provider call returns a typed result (`Accepted | Rejected | NotSent | OutcomeUnknown`, `Found | NotFound | Unavailable`); a timeout, 5xx or 409 is `OutcomeUnknown`, never failed, and no POST is ever retried.
3. `REQUEST_CUSTOMER_ACTION` creates a payment link with exactly the documented body (integer paise, attempt key as `reference_id`, no customer data); a 400 is resolved by lookup-by-reference, so one intent gets at most one link.
4. A created link leaves the intent EXECUTING; a campaign becomes RECOVERED only on `captured` evidence (link `paid` + payment `captured`), never on a 2xx, an `authorized` or an `active` status; a failed attempt on a link changes nothing (D1).
5. Evidence: 16 recorded Razorpay test-mode fixtures plus labelled doc-derived fixtures; tests 320 → 519 (`Failures: 0`, `Skipped: 1` = M2 tracking test); see `M1-provider-correctness.md` §12 and ADR-001 (Accepted).
6. NOT done: merchant-initiated debits (`RETRY_PAYMENT` is NotSupported in Razorpay mode; U8 skipped for lack of recurring recordings).
7. NOT done: claim fencing / lease safety during provider calls, a reconciler for EXECUTING intents with unknown outcomes, and link expiry/cancel handling (M2).
8. NOT done: per-merchant Razorpay credentials (single-tenant, fail-closed resolver until M5) and customer delivery of the link URL.
9. Evidence added at close-out: `20` (paid link) and `21` (captured payment, carrying the link's notes — verifies D1); still open: link-webhook recordings `22`–`24`, so the `payment_link.paid` shape stays AMBIGUOUS.
10. M2 must start with a failing test for lease expiry during an in-flight provider call (claim released while the call is still running), then the backlog below.

## M2 backlog from M1

Every out-of-scope finding recorded in `docs/m1/m1-progress.md`, plus the items the owner listed. None is implemented in M1.

1. **Lease expiry during an in-flight provider call.** The intent stays `CLAIMED` during the provider call; `releaseExpiredClaims()` can return it to READY and a second worker can call the provider again. Safe for links (provider `reference_id` dedup) but a duplicate-debit hazard once debits are wired. M2 should start with a test that reproduces it.
2. **Composite `campaign.paymentId`.** For real webhooks, `campaign.paymentId = event.externalEventId = "<eventType>_<entityId>"` (e.g. `payment.failed_pay_x`), not a Razorpay payment id. Anything needing the real `pay_` id must not assume it.
3. **Strategy-4 correlation of original-payment failures.** A late/duplicate `payment.failed` for the original payment correlates via campaign `paymentId` to the latest intent and fails an in-flight non-link intent. Tracked by `PaymentReconciliationTest.originalPaymentFailureMustNotFailNonLinkIntent` (`@Disabled("M2: correlation of original-payment failures")`). Link intents are protected by owner decision D1.
4. **Link expiry/cancel and dedup returning expired links.** `payment_link.expired` / `cancelled` events leave intents and campaigns EXECUTING (previously the "cancelled" substring counted as failure). Create-link dedup returns `Accepted(existing)` even when the existing link is expired or cancelled; a dedup amount/currency mismatch leaves a live, payable link at Razorpay under our reference.
5. **Unknown outcomes need a reconciler.** Intents left EXECUTING after `OutcomeUnknown` are resolved only by webhooks today; M2 owns the polling reconciler / `NEEDS_RECONCILIATION` state.
6. **Config errors exhaust campaigns.** `Rejected(AUTHENTICATION)` (incl. `NO_CREDENTIALS_FOR_MERCHANT`), `Rejected(LOCAL_PRECONDITION, AMOUNT_UNRESOLVED)` and `NotSent` take the FAILED→EXHAUSTED path although they are MEND-side/config problems; they should be non-terminal and operator-visible.
7. **MANUAL_REVIEW should route to the review queue.** In razorpay mode it is `NotSupported` → FAILED→EXHAUSTED (accepted for M1, D3); mock mode still simulates it.
8. **`ACTION_FAILED` / `ACTION_ERROR` audit drift.** The executor now writes `PROVIDER_REQUEST_*` / `PROVIDER_OUTCOME_UNKNOWN` instead; dashboards or analytics counting the old types will drift.
9. **`findByResponseReference` single-row assumption.** Returns `Optional`; two intents sharing a `responseReference` would throw and block webhook processing. The lookup is also not merchant-scoped (the later tenant check keeps it safe) and may be unindexed (an index needs a schema change).
10. **Order `receipt` is not unique** (recorded fixture `08`: two orders with the same receipt). If debits use orders, MEND must persist `order_id` before creating the payment and never create a second order per intent.
11. **Razorpay-owned debit retries vs MEND retries.** Razorpay may retry failed debits itself unless the order carries a `notification` object; MEND and Razorpay retries could overlap. Decide in the M2 ADR before wiring debits.
12. **Recurring debit adapter (U8) not built.** No test-mode recurring recordings (`30`–`33`); `RETRY_PAYMENT` stays NotSupported in razorpay mode.
13. **Customer delivery of the payment link.** M1 sends `notify:false` and no customer object; MEND has no channel to deliver `short_url` yet (DPDP review in M5 if contact data is passed).
14. **Per-merchant credentials** (M5): M1's resolver is single-tenant and fails closed for every other merchant.
15. **Link payments carry an `order_id`** (recording `21`). M2 reconciliation can use `GET /v1/orders/{order_id}/payments` (already a §6 operation) for link intents too, not only for debits — e.g. to resolve EXECUTING link intents without waiting for webhooks.
