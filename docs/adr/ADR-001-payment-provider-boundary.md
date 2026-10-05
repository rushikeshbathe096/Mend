# ADR-001 — Payment provider boundary and Razorpay operation set

Status: **Accepted** (2026-10-05, U9 close-out; open evidence items listed under "Evidence")
Date: 2026-10-05
Milestone: M1

## Context

MEND recovers failed Razorpay payments. Its single adapter (`RazorpayPaymentProviderClient`) was written without verification against Razorpay's API. Recovery decisions (`RECOVERED`, `FAILED`) are made directly from HTTP status classes.

## Problem

1. Three called endpoints (`/payments/{id}/retry`, `/subscriptions/{id}/charge`, `/payments/retry`) are absent from Razorpay's official API reference.
2. The one real endpoint (`/payment_links`) is called with a body Razorpay documents as invalid (missing `amount`, undocumented fields).
3. HTTP 2xx is treated as recovered money; timeouts and 5xx as failures; missing ids are fabricated.
4. Idempotency is attempted with headers Razorpay does not document for these operations.
5. The domain depends on a provider-shaped API (`executeAction`) that cannot express "accepted but not paid" or "outcome unknown".

## Options

1. Patch endpoints in place.
2. New domain port with typed operations and two-axis results (request outcome × payment evidence); Razorpay adapter split into transport, classifier, evidence mapper; idempotency through provider-native unique references.
3. Official Razorpay Java SDK behind the port.
4. Implement recurring debits now.

## Decision

Option 2.

- **Port:** `com.mend.provider.PaymentProvider` with `createPaymentLink`, `findPaymentLinkByReference`, `getPaymentLink`, `getPayment`. Debit operations (`createDebitOrder`, `initiateRecurringDebit`, `getOrderPayments`) are added only when test-mode recordings exist, and are not wired until M2.
- **Results:** `RequestResult = Accepted | Rejected | NotSent | OutcomeUnknown`; `LookupResult = Found | NotFound | Unavailable`; `PaymentEvidenceStatus` with `CAPTURED` as the only recovery status.
- **Attempt identity:** `ProviderAttemptKey = "mnd_" + hex(intentId)`, used as payment link `reference_id` (and order `receipt` later).
- **No automatic POST retries in the adapter.** Lost responses are resolved by lookup.
- **`RETRY_PAYMENT` in Razorpay mode** returns `Rejected(NOT_SUPPORTED)` locally.
- **Credentials:** resolver port; M1 implementation is single-tenant and fails closed for any other merchant.

## Why

- Payment links are the only recovery action that (a) is documented, (b) needs no mandate data MEND lacks, and (c) cannot cause a MEND-initiated duplicate debit.
- `reference_id` uniqueness + lookup-by-reference is the only documented dedupe + lost-response mechanism for links; headers are not.
- Separating request outcome from payment evidence is what makes invariant 6.3 enforceable in code instead of in reviewers' heads.
- The SDK collapses transport failures into one exception type, which would erase the NotSent/OutcomeUnknown distinction.

## Tradeoffs

- Merchant-initiated debit (MEND's original headline feature) is unavailable until M2 + mandate data. Honest absence over unsafe presence.
- More classes and a rewrite of three test files' assertions.
- One extra GET on create-link 400s.

## Consequences

- Legacy tests asserting invented endpoints are rewritten (reasons documented in the PR).
- `campaign RECOVERED` is reachable only via reconciliation on captured evidence.
- Intents whose provider outcome is unknown remain `EXECUTING` until M2's reconciler resolves them; operators can see them via audit type `PROVIDER_OUTCOME_UNKNOWN`.
- Webhook correlation gains `notes.mend_intent_id`.

## Evidence

- DOC: see `docs/m1/M1-provider-correctness.md` §4 (URLs, fetched 2026-10-05); create-link page re-checked at U9 the same day, unchanged (§12).
- RECORDED (Razorpay test mode, 2026-10-05, `backend/src/test/resources/razorpay/recorded/`): `01_create_link_ok`, `02_create_link_duplicate_ref`, `03_create_link_extra_fields`, `04_create_link_missing_amount`, `05_fetch_link_by_id`, `06_fetch_link_by_reference`, `07_create_order_ok`, `08_create_order_same_receipt`, `09_fetch_order_payments`, `10_legacy_payment_retry`, `11_legacy_subscription_charge`, `12_legacy_payments_retry`, `13_fetch_payment_not_found`, `14_bad_auth`.
  - `02` confirms `reference_id` uniqueness is enforced (the "at most one link per intent" guarantee holds). Its error text differs from the documented text; classification never relies on the text.
  - `08` shows order `receipt` is **not** unique (revisit condition below; affects debits only).
  - `10`–`12` show the three legacy endpoints do not exist (404 "no Route matched with those values").
  - `13` shows Razorpay answers a well-formed unknown payment id with 400 `BAD_REQUEST_ERROR` / `input_validation_failed`, mapped to NotFound for GET-by-id only.
- Doc-derived fixtures (`backend/src/test/resources/razorpay/doc-derived/`, each with `_source`): failed payment entity, 2xx without id, empty lookup, two links for one reference, paid link with a captured payment.
- Test results (copied from the summary files; raw logs are local-only):
  - Baseline `839f049`: `Tests run: 320, Failures: 0, Errors: 0, Skipped: 0` (`docs/m1/baseline-test-run-summary.txt`).
  - M1 final `b5077c9`: `Tests run: 516, Failures: 0, Errors: 0, Skipped: 1` (`docs/m1/m1-final-test-run-summary.txt`). The skipped test is the `@Disabled` M2 tracking test required by U7.
- U8 (recurring debit adapter): **skipped: no test-mode recurring access** (no fixtures `30`–`33`). `RETRY_PAYMENT` stays `NotSupported` in Razorpay mode.
- Implementation note: at the executor level the "Rejected(NOT_SUPPORTED)" decision is realised as `ProviderActionOutcome.NotSupported("RECURRING_DEBIT_NOT_ENABLED_UNTIL_M2")` → intent FAILED, audit `PROVIDER_REQUEST_REJECTED`, zero HTTP calls.
- Owner decisions during M1 (2026-10-05): D1 a failed payment on a link intent is a no-op (a link accepts several attempts); D2 the U6 grep gate applies to `backend/src/main` only; D3 `PaymentExecutionResult` kept as the executor report type and MANUAL_REVIEW → NotSupported in Razorpay mode; D4 U8 skipped, paid-link/webhook fixtures not recorded; D5 `docs/` tracked except raw logs.

**Open evidence items**
- Record paid-link + webhook fixtures (`20` paid link, `21` captured payment, `22`–`24` link/payment webhooks). Until then the `payment_link.paid` webhook name/shape stays AMBIGUOUS (§5), and D1's premise (whether a link attempt's payment carries the link's notes) is unverified.
- Recurring debit recordings (`30`–`33`) if debits are ever wired.

## Revisit conditions

- Razorpay documents idempotency headers for orders, payment links or recurring payments.
- U0 shows order `receipt` uniqueness is (or is not) enforced — update §10 guarantees.
- M2 exits → wire debit operations; decide on the order `notification` object vs Razorpay-owned retries.
- Multi-merchant use → M5 per-merchant credentials replace the single-tenant resolver.
- Any recording contradicts a DOC-based classification in §9.

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
