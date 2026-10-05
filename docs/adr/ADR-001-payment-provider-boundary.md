# ADR-001 — Payment provider boundary and Razorpay operation set

Status: **Proposed** (becomes Accepted when U0 recordings exist and U9 records test results)
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

- DOC: see `docs/m1/M1-provider-correctness.md` §4 (URLs, fetched 2026-10-05).
- RECORDED: **TBD** — fill with fixture file names after U0.
- Test results: **TBD** — fill after U9 (`./mvnw test` summary, counts before/after).

## Revisit conditions

- Razorpay documents idempotency headers for orders, payment links or recurring payments.
- U0 shows order `receipt` uniqueness is (or is not) enforced — update §10 guarantees.
- M2 exits → wire debit operations; decide on the order `notification` object vs Razorpay-owned retries.
- Multi-merchant use → M5 per-merchant credentials replace the single-tenant resolver.
- Any recording contradicts a DOC-based classification in §9.
