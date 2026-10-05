# U5 — Reconciler job, SAFE_RETRY, link TTL

**Production behaviour change. Spec = M2-execution-safety.md §4 and §7 decision table.**

## TASK
1. `ProviderCapabilities.supportsIdempotentResend(ActionType)`: Razorpay → true for REQUEST_CUSTOMER_ACTION, false otherwise. Mock → true for REQUEST_CUSTOMER_ACTION, **false for RETRY_PAYMENT** (synthetic debit has no idempotency — that is deliberate).
2. `MockPaymentProvider`: emulate `reference_id` uniqueness exactly as RECORDED fixture 02 (second create with the same key → the M1 lookup-on-400 path returns the existing link). Add `findPaymentLinkByReference` / `getPaymentLink` / `getPayment` behaviour consistent with stored synthetic links. Keep SYNTHETIC labelling.
3. `CampaignStateMachine`: add EXECUTING → ACTION_PENDING (reason code `RETRY_DEFERRED`). Test it.
4. New `service/ExecutionReconciler.java`, `@Scheduled(fixedDelayString="${mend.reconciler.poll-interval-ms:15000}")`, toggles `mend.reconciler.enabled` / `scheduled-enabled` mirroring ActionScheduler. Each cycle:
   a. `markStuck`: EXECUTING with `dispatched_at < now - T_stuck` → NEEDS_RECONCILIATION (conditional; does NOT require token — the worker is presumed dead; its later finalize will fail its token+EXECUTING predicate). Audit `EXECUTION_STUCK`.
   b. Pick up to N due rows (`status IN (NEEDS_RECONCILIATION, AWAITING_PAYMENT) AND reconcile_after <= now ORDER BY reconcile_after LIMIT :n FOR UPDATE SKIP LOCKED`, native query), in a short tx push `reconcile_after = now + leaseSeconds` and commit (reconcile lease).
   c. Outside tx, per row: lookups via `PaymentProvider` using the intent's merchant; apply §7 table via new conditional transitions (`reconcileTo`, `safeRetry`). Each applied transition sets `reconcile_attempts += 1` and the next `reconcile_after` with full-jitter backoff `uniform(0, min(cap, base·2^n))`.
   d. SAFE_RETRY: only if `supportsIdempotentResend` AND `now >= dispatched_at + grace` AND `execution_attempts < max`: intent → READY (same attempt key, token cleared, `dispatched_at` null), campaign EXECUTING → ACTION_PENDING, audit `SAFE_RETRY`. Otherwise (attempts exhausted or not resend-safe and budget spent) → FAILED + review item via `HumanApprovalService.createReview` with reason code `RECONCILIATION_ESCALATED` or `AMBIGUOUS_DEBIT`, campaign → FAILED → ELIGIBLE (so approval works — HumanApprovalService requires ELIGIBLE).
   e. SUCCEEDED → campaign RECOVERED via existing lifecycle service; FAILED (link expired/cancelled) → existing failure path.
5. `ProviderActionDispatcher`: set `expireBy = now + mend.recovery.link-ttl-hours` (default 72) on `CreatePaymentLink`; validate ≥ 15 min and ≤ 180 days (DOC) at startup.
6. Config defaults per ADR-002 OD-2/OD-3 in `application.properties`; tests use small values.
7. Un-disable S2, S3, S5 RED tests.

## FILES TO TOUCH
`ProviderCapabilities` (+ impls), `MockPaymentProvider`, `CampaignStateMachine`, new `ExecutionReconciler` (+ backoff policy class with injectable `RandomGenerator`/`Clock`), `ActionIntentTransitions`, repository native query, `ProviderActionDispatcher` (expireBy only), `application.properties`, tests.

## FILES NOT TO TOUCH
Razorpay adapter internals (only call its public port), webhook correlation (U8), migrations (V12 covers columns; if a column is missing, STOP).

## INVARIANTS
Reconciler never calls a create/POST operation. Never SAFE_RETRY a non-resend-safe action. Every row either progresses or gets a later `reconcile_after` (no hot loop).

## TEST REQUIREMENTS
Decision table: one test per row (mock + Razorpay stub server with fixtures 05/06/20/21 + doc-derived empty lookup). Two reconciler instances concurrently → each row processed once per lease (SKIP LOCKED). Backoff bounds with seeded random. Debit ambiguity → never READY, ends in review item. Liveness test: crash after dispatch → resolved within K reconciler cycles.

## DO NOT IMPLEMENT
Order-payments lookup (deferred, ADR-002), link cancel API (U8), unique index (U6), metrics registration.
