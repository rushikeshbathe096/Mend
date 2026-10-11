# ADR-002 — Execution safety: fencing, reconciliation, one-in-flight

Status: **Accepted** (M2 close-out, U10, 2026-10-11; proposed 2026-10-05)
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
RECORDED 02, 08, 20, 21 (M1).
- Test results: `docs/m2/m2-final-test-run-summary.txt` — `Tests run: 647, Failures: 0, Errors: 0, Skipped: 0` (M1 baseline 519 / 0 / 0 / 1). Exit tests: `M2ExitCriteriaTest` (§19, zero duplicate debits, 3 workers + manual path, crash after dispatch, 200 randomized races — seed printed, reproducible); three consecutive green full runs at U7 (`docs/m2/m2-progress.md`); the final code (U10) has one full run, as the close-out requires.
- Failure behaviour: every §9 row mapped to a green test in `docs/m2/M2-failure-table-verification.md`.
- Query plans: `docs/m2/M2-query-plans.md` (MEASURED, 100k intents / 5k merchants, laptop; no targets claimed).
- Payment-link cancel: `POST /v1/payment_links/{id}/cancel` verified from the official docs in U8 but **not recorded**, so MEND does not call it; the `DUPLICATE_PAYMENT_RISK` review fallback applies. `scripts/record_razorpay_contracts.sh --cancel-link` records it.
Open evidence: a real `payment.failed` webhook (for `invoice_key` derivation); link webhooks 22–24; the cancel-link recording (fixtures 40–44).

## Implementation notes (M2 close-out; decisions unchanged)
- Migrations V12–V15: the planned "V14 `provider_payment_id`" is V15 because V14 holds the U6 rule "intent invoice key = campaign invoice key" (FK). V15 also adds `campaigns.recovery_attribution` (`MEND` | `ORIGINAL_PAYMENT_LATE_CAPTURE`) so a late original capture is not credited to MEND.
- A late original capture on a campaign that has not acted yet (CREATED…SCHEDULED, ELIGIBLE) ends CANCELLED, not RECOVERED: the campaign state machine has no edge to RECOVERED from those states. Attribution is recorded either way.
- Deferrals and SAFE_RETRY share `execution_attempts` (cap 5 vs 3); both fail closed to review. A separate counter is an M3 candidate.
- §9 audit codes: a released undispatched claim is audited `CLAIM_RELEASED` (was `STATE_TRANSITION`, U10).
- Decision 10 / OD-1: a 429 is deferred only for actions with idempotent resend (links); otherwise it goes to review (decision 5). Approval of a MANUAL_REVIEW item re-runs compliance for the approved strategy; that decision is never reused without an approval.

## Revisit conditions
Debits enabled; Razorpay documents idempotency for orders or recurring payments; recorded failed-payment webhooks contradict the `invoice_key` derivation; reconciliation backlog grows beyond what polling handles (M5 measurement).
