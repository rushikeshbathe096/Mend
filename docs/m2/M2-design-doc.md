# DESIGN DOC — M2 Execution Safety

Companion: `M2-execution-safety.md` (§ numbers below refer to it).

## Problem
Intent execution safety relies on an undocumented coupling between intent claimability and campaign state. It fails for the manual start-recovery path (S1), for stale READY intents once retries exist (S5), and for multiple in-flight actions on one invoice (S7). Ambiguous or crashed executions are never resolved (S2–S4), because no reconciliation process uses provider lookups.

## Current behaviour
§1 and §2. Claim is atomic. Release is not conditional. Dispatch is not recorded. Finalize has no ownership check. `ActionIntent` has no optimistic locking. The manual path bypasses the claim. There is no one-in-flight constraint.

## Evidence
- Code: §1 (U0 refreshes file:line on current `main`).
- Provider: M1 recordings 02 (link `reference_id` uniqueness), 08 (order receipt not unique), 20/21 (paid link exposes captured payment id; captured payment carries link notes and an order id).
- Docs: Razorpay link `expire_by` must be ≥ 15 min ahead and ≤ 6 months (create-standard page, fetched 2026-10-05).

## Requirements
1. No path returns possibly-dispatched work to READY.
2. Every post-claim write is fenced by `claim_token`.
3. Every ambiguous or stuck execution is eventually resolved from provider evidence, or escalated to a human.
4. Automatic resend only where the provider enforces idempotency.
5. At most one in-flight action per (merchant, invoice), enforced by PostgreSQL.
6. All paths into execution use the same claim primitive.
7. Links terminate (bounded TTL).
8. MEND-side errors don't exhaust campaigns.
9. MANUAL_REVIEW never reaches the provider executor.
10. Original-payment events don't finalize recovery intents.

## Invariants
Brief 6.2 (one in-flight per invoice, DB-enforced), 6.3 (provider evidence only), 6.4 (compliance re-check stays in the same transaction as the dispatch fence), 6.5 (every transition audited). Plus all M1 invariants in `AGENTS.md`.

## Options

| | Option | Verdict |
|---|---|---|
| A | Longer lease | Rejected by §17; only narrows the window. |
| B | Distributed lock (Redis/ZooKeeper) with fencing tokens | Razorpay can't check tokens; adds a second source of truth. Rejected. |
| C | **DB-fenced state machine: conditional updates on `claim_token`, dispatch marker, `@Version`, partial unique index, reconciler using provider lookups, capability-gated resend** | **Chosen.** Uses the existing schema and the provider idempotency M1 verified. |
| D | SERIALIZABLE isolation for execution transactions | Retries everywhere, hard to reason about, still needs reconciliation. Rejected. |

## Decision
C. Details in §4–§8. Owner decisions recorded in ADR-002:
- **OD-1:** after a human approves a MANUAL_REVIEW item, the executed action is `REQUEST_CUSTOMER_ACTION` (payment link). Debits remain disabled.
- **OD-2:** link TTL default 72 h (`mend.recovery.link-ttl-hours`).
- **OD-3:** defaults — `max-dispatches-per-intent` 3, `T_stuck` 120 s, NotFound grace 120 s, reconcile backoff base 30 s, cap 6 h, MEND-side deferral cap 5.

Change any of these by editing ADR-002 before U1 starts.

## Architecture
- `ActionIntentTransitions` (new, repository layer): the only place that changes intent status after creation. Each method is a conditional UPDATE returning `boolean applied`. Methods: `claim`, `releaseIfUndispatched`, `dispatch`, `finalizeFromExecuting`, `markStuck`, `reconcileTo`, `safeRetry`, `cancel`.
- `ActionExecutionService`: prepare = existing checks + `dispatch(token)` in the same tx; call; `finalizeFromExecuting(token)`. Stale ⇒ discard + audit.
- `ExecutionReconciler` (new, `@Scheduled`, separate property toggle): picks work with `SKIP LOCKED`, performs lookups outside tx, applies the §7 decision table via `ActionIntentTransitions`.
- `ProviderCapabilities.supportsIdempotentResend(ActionType)` gates SAFE_RETRY.
- `InvoiceKeyResolver`: derives `invoice_key` at campaign creation; copied to each intent.
- Webhook reconciliation (M1 U7) switches its writes to `ActionIntentTransitions`; intent-specific correlation only (notes / responseReference / idempotency key); campaign-level correlation never finalizes an intent.
- Manual path: `CampaignController.startRecovery` calls `ActionIntentClaimService.claimSpecificIntent(intentId, workerId)` (atomic).
- `CampaignStateMachine`: add EXECUTING→ACTION_PENDING (reason `RETRY_DEFERRED`), used by SAFE_RETRY and MEND-side deferral.

## Failure modes
§9 table.

## Security implications
- No new external inputs. Reconciler only issues GETs with validated ids (M1 U4/U5 rules).
- Review items created by escalation contain codes and ids only, no PII.
- Tenant: all transitions keep `merchant_id` in the WHERE clause where the caller is merchant-scoped. The reconciler is system-scoped and must use each intent's own merchant for credentials.

## Testing strategy
1. **Red first (U1):** scenario tests for S1, S2, S3, S5, S6, S7 against current code, recording which fail. At least S1 and S2 are expected to fail.
2. Per-unit tests for each transition, positive and negative (stale token, wrong status).
3. Concurrency: real threads, `CountDownLatch`, the mock provider blocked mid-call, real PostgreSQL. Lease expiry simulated by moving `claimed_at`/`dispatched_at` back with SQL, not by sleeping.
4. **Exit tests (U7):** §19 scenario; 3 workers on one invoice; manual vs scheduler; crash after dispatch; repeated 200× with randomized interleavings.
5. The mock provider must emulate `reference_id` uniqueness (RECORDED 02) so duplicate-link assertions are meaningful. The mock debit has **no** idempotency, which is what makes "zero duplicate debits" a real test.

## Metrics
Reserved names in §9; emitted as structured log lines in M2, registered in M5. Measured query latencies: **TBD** (U10).

## Tradeoffs
- More states and a background job, which is real complexity. It's justified by S2–S4: without it, ambiguous outcomes are never resolved.
- Ambiguous debits go to humans rather than auto-resolving. That's slower, but a duplicate debit is the worst failure in the system.
- `invoice_key` derivation is partly AMBIGUOUS; the fallback equals today's behaviour.

## Exit criteria
Brief §49, mapped in `M2-implementation-plan.md`.
