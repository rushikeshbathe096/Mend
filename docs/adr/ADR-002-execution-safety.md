# ADR-002 — Execution safety: fencing, reconciliation, one-in-flight

Status: **Proposed** (Accepted at M2 close-out, U10)
Date: 2026-10-05 · Milestone: M2 · Depends on: ADR-001

## Context
M1 made provider calls correct and introduced non-terminal outcomes (`OutcomeUnknown`, `LinkIssued`) that leave intents in EXECUTING. Execution concurrency is protected only by campaign-state coupling (`docs/m2/M2-execution-safety.md` §2).

## Problem
Duplicate provider calls are possible (manual path S1; stale READY intents once retries exist S5; several in-flight actions per invoice S7). Ambiguous or crashed executions are never resolved (S2–S4). Stale workers can overwrite state (S6).

## Options
Longer lease; distributed lock with fencing tokens; DB-fenced state machine with provider-evidence reconciliation; SERIALIZABLE isolation. See the design doc.

## Decision
1. **Fence rows in PostgreSQL.** Every post-claim status write is a conditional UPDATE on `(id, status, claim_token)`. `ActionIntent` gets `@Version`.
2. **Record dispatch before the provider call** (`dispatched_at`, CLAIMED→EXECUTING), in the same transaction as the compliance re-check.
3. **Release only undispatched claims.** Stuck EXECUTING becomes NEEDS_RECONCILIATION, never READY.
4. **Reconcile from provider evidence** with a `SKIP LOCKED` reconciler (decision table, §7).
5. **Resend only when the provider enforces idempotency** (`supportsIdempotentResend`): links yes (RECORDED 02), debits no (RECORDED 08; no idempotency header). Ambiguous debits go to human review.
6. **One in-flight action per (merchant, invoice_key)**, enforced by a partial unique index; `invoice_key` derived from `invoice_id` → `order_id` → payment id.
7. **One claim primitive** for scheduler and manual paths.
8. **Bounded link lifetime** (`expire_by`, default 72 h).
9. **Original-payment (campaign-level) events never finalize a recovery intent.**
10. **MEND-side errors defer, not exhaust**; the cap leads to human review. MANUAL_REVIEW creates a review item, never an executable intent.

## Owner decisions
- OD-1: after manual-review approval, the action is REQUEST_CUSTOMER_ACTION (payment link).
- OD-2: link TTL 72 h.
- OD-3: max dispatches/intent 3; T_stuck 120 s; NotFound grace 120 s; reconcile backoff 30 s base, 6 h cap, full jitter; MEND-side deferral cap 5.

## Why
- The protected resources are MEND rows (fenceable by the DB) and provider resources (protectable only by provider idempotency). Classic token fencing protects neither better than this.
- Safety argument for link resend: identical `reference_id` ⇒ the provider rejects a duplicate ⇒ M1's lookup-on-400 returns the existing link. It holds whatever the timing, which is why it can survive GC pauses and stale lookups.
- No equivalent argument exists for debits, so debits never auto-resend.

## Tradeoffs
Background reconciler and more states; slower resolution for ambiguous debits; `invoice_key` partly AMBIGUOUS (fallback = current behaviour).

## Consequences
- Legacy EXECUTING rows are migrated: with a `plink_` reference → AWAITING_PAYMENT, otherwise → NEEDS_RECONCILIATION.
- The migration fails loudly if existing data already violates one-in-flight. The runbook (in V12's header comment) says how to inspect and resolve it.
- `CampaignStateMachine` gains EXECUTING→ACTION_PENDING.
- Dashboards gain states AWAITING_PAYMENT and NEEDS_RECONCILIATION (frontend display mapping may need an update; tracked, not M2).

## Deferred (not M2)
- Debit wiring, order-based debit reconciliation, `notification` object vs Razorpay-owned retries (until debits are enabled).
- Payment-link cancellation API: only if U8 can verify the endpoint from official docs and record it. Otherwise local cancellation + review flag `DUPLICATE_PAYMENT_RISK`.
- ACTION_FAILED/ACTION_ERROR dashboard drift, metrics registration, alerts (M5).
- AI (M3).

## Evidence
RECORDED 02, 08, 20, 21 (M1). Test results: **TBD** (U10). Query plans: **TBD** (U10).
Open evidence: a real `payment.failed` webhook (for `invoice_key` derivation); link webhooks 22–24.

## Revisit conditions
Debits enabled; Razorpay documents idempotency for orders or recurring payments; recorded failed-payment webhooks contradict the `invoice_key` derivation; reconciliation backlog grows beyond what polling handles (M5 measurement).
