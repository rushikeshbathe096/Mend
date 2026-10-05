# M2 — EXECUTION SAFETY: analysis

Status: **Design complete, no implementation.**
Prepared 2026-10-05 from the pre-M1 source snapshot plus the M1 records (`docs/m1/m1-progress.md`, `M1-summary.md`). The claim, scheduler, lease and campaign state-machine code was **not** modified by M1. The executor (`ActionExecutionService`) and webhook reconciliation **were** (U6, U7).
**U0 re-verifies every reference below against current `main` before any code is written.**

Brief references quoted so this document stands alone:

> §16 Dangerous: CLAIMED → lease expires → releaseExpiredClaims() → READY, without knowing whether the provider call completed.
> §17 Target: CLAIMED → provider call → response? YES → evidence; NO → NEEDS_RECONCILIATION → status lookup → SUCCESS / FAILED / SAFE RETRY. Do not solve by increasing lease duration.
> §18 Fencing: a stale worker must not perform protected work after its claim is invalid. Design from the existing schema, not a textbook lock.
> §19 Exit test: A claims → A starts provider call → lease expires → B attempts → ambiguous outcome → reconciliation → final state. Required: zero duplicate debits.
> §49 Exit: fencing; claim expiry cannot return in-flight work to READY; NEEDS_RECONCILIATION; reconciliation decides retry safety; one-in-flight DB-enforced; concurrency test; lease-expiry-during-call test; zero duplicates; existing tests pass; failure behaviour documented.

---

## §0 Verification on main (27658bb)

U0, 2026-10-05, branch `m2/execution-safety` cut from `main` @ `27658bb`. Paths are relative to `backend/src/main/java/com/mend/` unless they start with `resources/`. Baseline: `Tests run: 519, Failures: 0, Errors: 0, Skipped: 1` (`docs/m2/m2-baseline-test-run-summary.txt`).

### §1 rows

| Row | Evidence | Verdict |
|---|---|---|
| Claim | `service/ActionIntentClaimService.java:61-99` (`claimDueIntents`, `REQUIRES_NEW`; promote, then select up to batch READY intents with campaign ACTION_PENDING; per-intent `claimIntentAtomic` with a fresh UUID token at :73-82). Predicate `domain/repository/ActionIntentRepository.java:78-106`: `status = :expected AND scheduledAt <= :claimedAt AND EXISTS (campaign with same merchant AND currentState = :expectedCampaignStatus)`. | CONFIRMED (predicate also requires `scheduledAt <= claimedAt` and campaign merchant = intent merchant) |
| Lease release | `ActionIntentClaimService.java:101-128`: `findExpiredClaims` (`ActionIntentRepository.java:72-73`, `status='CLAIMED' AND claimedAt < threshold`), then unconditional `setStatus(READY)` + `save` per row, audit `STATE_TRANSITION`. Lease `mend.scheduler.lease-duration-minutes`, default 5 (:35). | CONFIRMED |
| Window expiry | `ActionIntentClaimService.java:130-156`, query `ActionIntentRepository.java:75-76`. | CONFIRMED |
| Scheduler | `scheduler/ActionScheduler.java:49-93`: release, expire, claim, then serial execute; `fixedDelay` `mend.scheduler.poll-interval-ms` 5000; worker id per instance (:44-46). | CONFIRMED |
| Executor | `service/ActionExecutionService.java`: prepare :113-197 (CLAIMED check :118; campaign ACTION_PENDING to EXECUTING :169-178; returns `proceed` without changing intent status); provider dispatch outside tx :101; finalize :199+ with only an `isTerminal()` guard :209. | CONFIRMED (M1 changed the call and outcome mapping, not prepare) |
| Manual path | `controller/CampaignController.java:164-178`: sets CLAIMED + new worker id + `claimedAt` via `actionIntentRepository.save` (:168-171), then executes synchronously. No `claimIntentAtomic`. | CONFIRMED |
| Webhook reconciliation | `service/DefaultPaymentReconciliationService.java:142-147` (SUCCEEDED) and :183-185 (FAILED) via `setStatus` + `save`. | CONFIRMED (CHANGED-BY-M1: U7 evidence rules + D1) |
| Row concurrency control | `domain/entity/Campaign.java:54` has `@Version`; `domain/entity/ActionIntent.java` has none. Status column `resources/db/migration/V1__create_initial_schema.sql:122` `VARCHAR(20) NOT NULL DEFAULT 'PENDING'`, no CHECK. | CONFIRMED |
| DB uniqueness | V1:123 `idempotency_key ... UNIQUE`; V1:108 `UNIQUE(campaign_id, attempt_number)`; `V5__campaign_idempotency_and_relations.sql:5-7` partial unique `(merchant_id, payment_id) WHERE payment_id IS NOT NULL`. No one-in-flight constraint. | CONFIRMED |
| Intent statuses | `domain/enums/ActionIntentStatus.java:3-21`: 10 values; terminal SUCCEEDED, FAILED, CANCELLED, EXPIRED (:15-17). | CONFIRMED |
| Campaign transitions | `statemachine/CampaignStateMachine.java:19-30`. ACTION_PENDING to EXECUTING/ELIGIBLE/RECOVERED/CANCELLED (:23); EXECUTING to RECOVERED/FAILED/CANCELLED (:24), no EXECUTING to ACTION_PENDING yet; FAILED includes ACTION_PENDING (:25). | CONFIRMED |
| Since M1 | EXECUTING set for LinkIssued at `ActionExecutionService.java:292` and Unknown at :302; only a webhook moves it out. | CONFIRMED (CHANGED-BY-M1) |

### Specific checks (a)–(h)

| # | Question | Finding |
|---|---|---|
| a | Intent CLAIMED during the provider call? | Yes. `prepareExecution` (:113-197) never changes the intent status; the dispatch at :101 runs while it is CLAIMED. |
| b | Ownership check in finalize? | None. `finalizeExecution(..., workerId, ...)` ignores `workerId`; the only guard is `isTerminal()` (:209). |
| c | Manual claim via plain save? | Yes, `CampaignController.java:168-171`. |
| d | `@Version` on `ActionIntent`? | No. |
| e | Highest Flyway migration? | `V11__create_transactional_outbox.sql`, so M2 starts at **V12**. Timestamp types: V8 added `scheduled_at`, `claimed_at`, `expires_at` as `TIMESTAMP WITH TIME ZONE` (V8:7-11); V1 `created_at`/`completed_at` are `TIMESTAMP`. New M2 columns follow V8 (`TIMESTAMPTZ`). |
| f | Where M1 sets EXECUTING | `ActionExecutionService.java:292` (LinkIssued) and :302 (Unknown). |
| g | Mock repeated `reference_id`? | `provider/mock/MockPaymentProvider.java:58` uses `linksByReference.computeIfAbsent(key, ...)`: a second create with the same key returns `Accepted(existing snapshot)` directly. It does **not** emulate fixture 02's 400-then-lookup path, but the observable result (same link, no second resource) matches. U5 makes it explicit. |
| h | `ProviderCapabilities` methods | Only `supportsRecurringDebit()` (`provider/ProviderCapabilities.java:6`). `supportsIdempotentResend` is new in U5. |

### Scenarios S1–S11

| S | Evidence on main | Verdict |
|---|---|---|
| S1 manual vs scheduler | The controller overwrites CLAIMED + worker via save (:168-171). Its `prepareExecution` passes: the intent is CLAIMED, the worker id is its own, and EXECUTING is not in the non-executable campaign set (CANCELLED/EXHAUSTED/RECOVERED/FAILED). A second dispatch is possible while the scheduler's call is in flight. | CONFIRMED |
| S2 crash after prepare | Intent CLAIMED with campaign EXECUTING; release sets READY (:101-128); the claim predicate needs campaign ACTION_PENDING, so it is never claimable again; nothing reconciles it. | CONFIRMED |
| S3 unknown outcome | Unknown sets EXECUTING (:302); no job ever looks it up. | CONFIRMED |
| S4 link never paid | LinkIssued sets EXECUTING (:292); the M1 dispatcher passes `expireBy = null` (`service/ProviderActionDispatcher.java`, `new CreatePaymentLink(..., null, ...)`). | CONFIRMED |
| S5 stale READY re-claimed | Release sets READY with campaign EXECUTING; FAILED to ACTION_PENDING is allowed (`CampaignStateMachine.java:25`), after which the READY intent satisfies the claim predicate again. | CONFIRMED (needs a path back to ACTION_PENDING; U1 drives it explicitly) |
| S6 stale finalize | Extra concrete path found: because the intent stays CLAIMED during the call, `releaseExpiredClaims` can flip it to READY mid-call; finalize then sees a non-terminal status and overwrites it (:209). | CONFIRMED (plus the release path) |
| S7 two in flight per invoice | No one-in-flight constraint; campaign key `(merchant_id, payment_id)` with the composite `payment_id`. | CONFIRMED |
| S8 original payment failed correlates to latest intent | Strategy 4 (`DefaultPaymentReconciliationService.java:257-263`) matches `campaign.paymentId` against the payload's raw `pay_` id. For webhook-created campaigns `paymentId` is the composite `payment.failed_pay_x`, so strategy 4 **never matches real webhooks**. It bites campaigns created with a raw id (manual start-recovery and `POST /campaigns` use `request.paymentId` verbatim) and dev seed data. Link intents are already protected by M1 D1. | CORRECTION (narrower scope; U8's response unchanged, and needed once V14 makes campaign-level matching work) |
| S9 late original capture | Same strategy-4 condition as S8; when it matches, captured sets the intent SUCCEEDED and the campaign RECOVERED. | CORRECTION (same narrower scope as S8) |
| S10 MEND-side errors exhaust | `ActionExecutionService.failIntentAndCampaign` takes FAILED then EXHAUSTED for Rejected (incl. AUTHENTICATION, LOCAL_PRECONDITION AMOUNT_UNRESOLVED, RATE_LIMITED), NotSupported and NotSent. | CONFIRMED |
| S11 MANUAL_REVIEW | **Wrong mechanism.** `compliance/ComplianceEngine.java:107-113` blocks strategy MANUAL_REVIEW, so `ActionIntentService.createActionIntentFromCompliance` (:75-79) throws `ComplianceBlockedException` and creates **no** intent. All runtime intents are created there (the only other `new ActionIntent` is dev seed data). Approving a review for a MANUAL_REVIEW-strategy campaign re-runs the same check (`service/HumanApprovalService.java:198`) and fails the same way, so the case can be neither executed nor approved. | CORRECTION (U9/OD-1 response still valid; "legacy MANUAL_REVIEW intents reaching the executor" do not occur at runtime; the executor NotSupported path today is RETRY_PAYMENT in razorpay mode) |

**Design impact:** no finding invalidates a design decision. S8/S9 are narrower than stated and S11's mechanism differs, but U8 and U9 address them as designed. S6 gains a second concrete path (release mid-call), which U3's fences close.

---

## 1. Current execution machinery

| Piece | Where | Behaviour |
|---|---|---|
| Claim | `ActionIntentClaimService.claimDueIntents` | One `REQUIRES_NEW` tx: promote SCHEDULED→READY, select ≤50 READY intents whose campaign is `ACTION_PENDING`, then for each run `claimIntentAtomic` (conditional UPDATE `WHERE status=READY AND campaign.currentState=ACTION_PENDING`) setting CLAIMED, `claimed_at=now`, a fresh UUID `claim_token`, `worker_id`. |
| Lease release | `ActionIntentClaimService.releaseExpiredClaims` | Loads every `status='CLAIMED' AND claimed_at < now − lease` (lease default 5 min) and **unconditionally** sets READY, nulls token/worker, via entity `save`. Audits. |
| Window expiry | `processExpiredIntents` | SCHEDULED/READY/PENDING past `expires_at` → EXPIRED. |
| Scheduler | `ActionScheduler.pollAndExecuteDueActions` | Each poll: release → expire → claim batch → execute each claimed intent **serially**, `fixedDelay` 5 s, one thread per instance; multiple instances allowed (worker id per instance). |
| Executor | `ActionExecutionService.executeActionIntent` | TX1 `prepareExecution`: intent must be CLAIMED; worker check only if both ids non-null; compliance re-check; campaign ACTION_PENDING→EXECUTING; audit. **Intent stays CLAIMED.** Then provider call outside tx (M1 dispatcher). TX2 `finalizeExecution`: reload; skip if intent terminal; map outcome (M1 table). **No ownership check in finalize.** |
| Manual path | `CampaignController.startRecovery` | After orchestration, sets the returned intent to CLAIMED with a new worker id via plain `actionIntentRepository.save(...)` — **bypasses `claimIntentAtomic`** — then calls the executor synchronously. |
| Webhook reconciliation | `DefaultPaymentReconciliationService` (M1 U7) | Correlates and sets intent SUCCEEDED/FAILED via entity `save`. |
| Concurrency control on rows | — | `Campaign` has `@Version`. **`ActionIntent` has no `@Version`.** Status column `VARCHAR(20)`, no CHECK constraint. |
| DB uniqueness | V1, V5 | `action_intents.idempotency_key` UNIQUE; `campaign_attempts (campaign_id, attempt_number)` UNIQUE; `campaigns (merchant_id, payment_id)` UNIQUE partial. **No one-in-flight constraint.** |
| Intent statuses | `ActionIntentStatus` | PENDING, SCHEDULED, READY, CLAIMED, PROCESSING (unused), EXECUTING, SUCCEEDED, FAILED, CANCELLED, EXPIRED. Terminal: SUCCEEDED, FAILED, CANCELLED, EXPIRED. |
| Campaign transitions | `CampaignStateMachine` | ACTION_PENDING→{EXECUTING, ELIGIBLE, RECOVERED, CANCELLED}; EXECUTING→{RECOVERED, FAILED, CANCELLED}; FAILED→{SCHEDULED, ELIGIBLE, ACTION_PENDING, EXHAUSTED, CANCELLED, RECOVERED}. |
| Since M1 | executor / reconciliation | Unknown outcome or LinkIssued ⇒ intent **EXECUTING** (non-terminal); nothing ever moves it out except a webhook. |

## 2. Scenario analysis (what actually happens today)

The brief's §16 scenario assumes B can re-claim A's in-flight intent. Tracing the code shows that path is **blocked — but only by accident**:

```text
A: claim (intent CLAIMED, campaign ACTION_PENDING)
A: prepareExecution commits  → campaign EXECUTING, intent still CLAIMED
A: provider call starts … slow …
lease expires
any worker: releaseExpiredClaims → intent READY (campaign still EXECUTING)
B: claimIntentAtomic requires campaign ACTION_PENDING → 0 rows → B cannot claim
```

The guard is the coupling between intent claimability and campaign state. Nobody designed it as a fence, nothing documents it, and it fails in these cases:

| # | Scenario | Result today | Severity |
|---|---|---|---|
| S1 | **Manual start-recovery vs scheduler.** Orchestrator creates intent READY + campaign ACTION_PENDING; scheduler claims and prepares (campaign→EXECUTING) and calls the provider; controller then `save`s the intent as CLAIMED with its own worker id; controller's `prepareExecution` sees CLAIMED + matching worker → passes (campaign EXECUTING isn't rejected) → **second provider call**. | Duplicate provider call possible. For links: second call hits `reference_id` uniqueness → dedup (M1 U4) → no duplicate link. For a debit: **duplicate debit**. | HIGH (debit path), MED (links) |
| S2 | **Worker crash after prepare, during the provider call.** | Intent released to READY, campaign stuck EXECUTING → intent never claimable again, never reconciled. If the call succeeded, the link exists but MEND doesn't know its id until a webhook arrives (if ever). | HIGH (liveness; silent) |
| S3 | **Unknown outcome (timeout/5xx).** | Intent EXECUTING forever unless a webhook happens to correlate. No lookup ever runs. | HIGH (liveness) |
| S4 | **Link issued, customer never pays.** | Intent EXECUTING for the link lifetime (Razorpay default 6 months, `expire_by` not set by M1). Campaign EXECUTING. No polling fallback if webhooks are missed. | MED |
| S5 | **Released intent + campaign later re-enters ACTION_PENDING** (any future retry path, or FAILED→ACTION_PENDING which the state machine already allows). | The stale READY intent becomes claimable → re-execution of an attempt whose first execution may have succeeded. | HIGH once M2 adds retries — must be designed out, not left to chance |
| S6 | **Stale finalize.** A's finalize after B (or a webhook) moved the intent. | `finalizeExecution` only checks `isTerminal`. If the other actor left it non-terminal (e.g. EXECUTING), A's result overwrites it. No ownership check, no `@Version`. | MED |
| S7 | **Two intents for the same invoice in flight.** Different `ActionType`, or two campaigns for the same underlying invoice (campaign key is `(merchant, payment_id)` and `payment_id` is the composite `"payment.failed_pay_x"`, so every failed payment of the same subscription invoice creates a new campaign). | Two live payment links for one debt → customer may pay twice. Nothing in the DB prevents it. | HIGH (customer money) |
| S8 | **Original payment's own `payment.failed` webhook** correlates (strategy 4) to the latest intent and marks it FAILED (M1 `@Disabled` test). | Live link intent killed by an unrelated event. | MED |
| S9 | **Late capture of the original payment** while a recovery link is live. | Campaign-level correlation may mark the *intent* SUCCEEDED (wrong attribution), link stays live → customer could pay twice. | MED (needs evidence; see §6) |
| S10 | **MEND-side errors** (`NO_CREDENTIALS_FOR_MERCHANT`, `AMOUNT_UNRESOLVED`, `NotSent`, `RATE_LIMITED`). | Intent FAILED, campaign FAILED→**EXHAUSTED** (terminal) for a problem that was ours. | MED (lost recoveries) |
| S11 | **MANUAL_REVIEW strategy** produces an executable intent; in razorpay mode → NotSupported → EXHAUSTED. Review approval re-runs compliance and may produce MANUAL_REVIEW again. | Human-review cases exhausted instead of reviewed. | MED |

Honest conclusion: the brief's flagship scenario is not reproducible as written, but the system is still unsafe in S1, S5 and S7 (duplicates) and S2–S4 (liveness). M2 replaces the accidental guard with explicit, tested fences.

## 3. Why the fix is NOT "increase the lease" or "add a distributed lock"

- A longer lease only shortens the window. GC pauses, slow providers and crashes still exceed any finite lease (§17 forbids this anyway).
- Classic fencing tokens matter when the **protected resource** checks the token. Razorpay does not check MEND tokens. The protected resources are (a) MEND's own rows — which PostgreSQL can fence with conditional updates — and (b) the provider resource — which can only be protected by **provider-side idempotency**. M1 established that for payment links (`reference_id` uniqueness, RECORDED fixture 02) and established that **no such mechanism exists for debits** (receipt not unique, RECORDED fixture 08; no idempotency header).
- Therefore: rows are fenced by the DB; provider resources are protected by provider idempotency where it exists, and **by refusing automatic resend where it does not**.

## 4. Target state machine

```text
PENDING/SCHEDULED ─promote→ READY ─claim(token)→ CLAIMED
                                    ▲              │ lease expired AND not dispatched (conditional)
                                    │              └──────────────→ READY
                                    │              │ dispatch fence (token): set dispatched_at, attempts++
                                    │              ▼
                                    │          EXECUTING ── provider call (no tx) ──┐
                                    │              │ worker dead / stuck > T_stuck   │ finalize(token)
                                    │              ▼                                 ▼
          SAFE_RETRY (only if       │   NEEDS_RECONCILIATION ◀── OutcomeUnknown ─────┤
          provider resend is        │              │ reconciler lookups              │
          idempotent: links)  ──────┘              ├── found, live link ──→ AWAITING_PAYMENT ◀── Accepted(link)
                                                   ├── found, paid+captured ──→ SUCCEEDED
                                                   ├── found, expired/cancelled ──→ FAILED
                                                   └── not found / unavailable ──→ stay (backoff) or SAFE_RETRY
          AWAITING_PAYMENT ── webhook or poll: PAID+CAPTURED ──→ SUCCEEDED
                          └── EXPIRED/CANCELLED ──→ FAILED
          any non-terminal ── campaign cancelled ──→ CANCELLED
```

In-flight set (one per invoice): **CLAIMED, EXECUTING, NEEDS_RECONCILIATION, AWAITING_PAYMENT.**
`NEEDS_RECONCILIATION` is exactly 20 characters and fits the existing `VARCHAR(20)` column.

Rules:
- Only CLAIMED with `dispatched_at IS NULL` may be released by lease expiry.
- EXECUTING is never released. Stuck EXECUTING (older than `T_stuck`) becomes NEEDS_RECONCILIATION.
- SAFE_RETRY returns the **same intent** (same attempt key) to READY. It is allowed only when `ProviderCapabilities.supportsIdempotentResend(actionType)` is true. Links: true (provider-enforced `reference_id`). Debits: **false** — an ambiguous debit is never auto-resent; it goes to human review after the reconciliation budget is spent.

## 5. Fencing design (based on the existing schema)

- **Ownership fence:** the existing `claim_token` (UUID, already written by `claimIntentAtomic`). Every write after claiming is a conditional UPDATE `WHERE id=:id AND status=:expected AND claim_token=:token`. 0 rows ⇒ stale actor ⇒ abort (before dispatch) or discard result + audit `STALE_WORKER_RESULT_DISCARDED` (after dispatch). Monotonic tokens are unnecessary because the fenced resource is a single DB row compared by equality; the token never leaves MEND.
- **Lost-update fence:** add `@Version` (`version BIGINT`) to `ActionIntent` so remaining entity-`save` paths (webhook reconciliation, cancellation) fail loudly instead of silently overwriting.
- **Dispatch marker:** `dispatched_at` set by the CLAIMED→EXECUTING transition in the same transaction as the compliance re-check, *before* the provider call. It is what makes "lease expired" distinguishable from "call possibly in flight".
- **Manual path:** `startRecovery` must claim through the same atomic claim (or a single-intent variant of it), never by `save`.

## 6. Invoice identity (one in-flight per invoice)

`campaigns.payment_id` holds `"<eventType>_<entityId>"` (M1 U3 finding). Razorpay's payment entity documents `order_id` and `invoice_id` (payments entity page), and recording 21 shows a link payment carrying an `order_id`. Proposed `invoice_key`, derived once from the original failure webhook:

```text
invoice_key = "inv:" + invoice_id   if present  (subscription / invoice payments)
            | "ord:" + order_id     if present
            | "pay:" + payment id   otherwise (today's behaviour: one key per failed payment)
```

Evidence status: field existence = DOC + RECORDED (21, a link payment). Whether *failed* subscription payments always carry `invoice_id` = **AMBIGUOUS** (no recorded failed-payment webhook). The fallback reproduces current behaviour, so the derivation is never less safe than today. Recording a real `payment.failed` webhook is listed as open evidence.

`invoice_key` is copied onto `action_intents` (a partial unique index cannot span tables), and enforced by:

```sql
CREATE UNIQUE INDEX ux_action_intents_one_inflight_per_invoice
  ON action_intents (merchant_id, invoice_key)
  WHERE status IN ('CLAIMED','EXECUTING','NEEDS_RECONCILIATION','AWAITING_PAYMENT');
```

A per-campaign variant is implied, since a campaign has one invoice key.

## 7. Reconciler decision table (links)

| Intent state | Lookup | Result | Transition |
|---|---|---|---|
| NEEDS_RECONCILIATION | `findPaymentLinkByReference(key)` | Found, CREATED/PARTIALLY_PAID | → AWAITING_PAYMENT, store plink id |
| | | Found, PAID with captured payment id → `getPayment` CAPTURED | → SUCCEEDED, campaign RECOVERED |
| | | Found, EXPIRED/CANCELLED | → FAILED (campaign failure path) |
| | | NotFound, and `now ≥ dispatched_at + grace` | SAFE_RETRY → READY (same key), campaign EXECUTING→ACTION_PENDING; if `execution_attempts ≥ max` → FAILED + review item |
| | | Unavailable | stay, backoff |
| AWAITING_PAYMENT | `getPaymentLink(id)` (webhook fallback) | PAID + captured → `getPayment` CAPTURED | → SUCCEEDED |
| | | EXPIRED/CANCELLED | → FAILED |
| | | CREATED/PARTIALLY_PAID | stay, backoff (grows to cap) |
| | | NotFound | → NEEDS_RECONCILIATION + WARN (should not happen) |
| EXECUTING older than `T_stuck` | — | — | → NEEDS_RECONCILIATION |
| NEEDS_RECONCILIATION, debit (`supportsIdempotentResend=false`) | (no debit lookup in M2) | — | stays; after budget → review item `AMBIGUOUS_DEBIT`; **never** SAFE_RETRY |

Why no order lookup (backlog item 15) in M2: recording 20 shows a paid link's `payments[]` carries the captured payment id, and `getPayment` (recording 21) confirms CAPTURED. `GET /orders/{id}/payments` adds nothing for links. Deferred until debits are wired. That is an evidence-based deferral.

Why NotFound ⇒ resend is safe for links even if Razorpay's lookup is momentarily stale: the resend carries the same `reference_id`; if the first request did land, Razorpay rejects the duplicate (fixture 02) and M1's lookup-on-400 returns the existing link. Worst case is one extra API call, never a second link. **This argument does not hold for debits**, hence the capability gate.

Link lifetime: M2 sets `expire_by = now + mend.recovery.link-ttl-hours` (default 72 h; Razorpay requires ≥ 15 min in future and ≤ 6 months, DOC) so AWAITING_PAYMENT always terminates.

## 8. Algorithms and data structures (brief §30)

| Problem | Naive | Chosen | Structure | Complexity | Alternative / tradeoff |
|---|---|---|---|---|---|
| Find due intents | full scan + sort in Java | ordered DB query on `(status, scheduled_at)` index (exists, V8) with `LIMIT` | B-tree | O(log n + k) | In-memory priority queue: faster per poll, but not shared across instances and lost on crash. Rejected: DB is the source of truth and already indexed. |
| Pick reconciliation work across instances | everyone polls everything | `SELECT … WHERE status IN (…) AND reconcile_after <= now ORDER BY reconcile_after LIMIT k FOR UPDATE SKIP LOCKED` in a short tx that pushes `reconcile_after` forward (acts as a lease) | partial B-tree index on `(reconcile_after) WHERE status IN (…)` | O(log n + k) | Advisory locks: more moving parts. Redis lock: second source of truth. Rejected. |
| Retry/poll backoff | fixed interval | `delay_n = min(cap, base·2^n)`, **full jitter** `uniform(0, delay_n)` | counter `reconcile_attempts` | O(1) | Equal jitter keeps a minimum delay; full jitter spreads load best for many intents created together (campaign batches). |
| One in-flight per invoice | app-level check | partial unique index | B-tree | O(log n) per write | Serializable isolation: heavier, harder to reason about; the index is the invariant. |
| Stale actor detection | timestamps | equality on `claim_token` in conditional UPDATE | — | O(1) | Monotonic epoch: needed only if an external resource checks the token; none does. |

Measured performance: **TBD** (U10 measures poll and reconcile query latency on a seeded 100k-intent table with `EXPLAIN ANALYZE`; results recorded, not targets).

## 9. Failure behaviour (brief §37, M2 scope)

| Failure | Detection | Recovery | Audit | Metric (reserved, M5) |
|---|---|---|---|---|
| Worker crash before dispatch | CLAIMED, `dispatched_at` null, lease expired | release → READY (conditional) | `CLAIM_RELEASED` | `mend_claims_released_total` |
| Worker crash after dispatch | EXECUTING older than `T_stuck` | → NEEDS_RECONCILIATION → reconciler | `EXECUTION_STUCK` | `mend_executions_stuck_total` |
| Provider timeout / 5xx | `OutcomeUnknown` | → NEEDS_RECONCILIATION | `PROVIDER_OUTCOME_UNKNOWN` | `mend_reconciliation_backlog` |
| Stale worker finalize | conditional update 0 rows | result discarded; reconciler or other actor owns truth | `STALE_WORKER_RESULT_DISCARDED` | `mend_stale_results_total` |
| Concurrent claim of 2nd action for same invoice | unique index violation / NOT EXISTS predicate | not claimed; retried next poll | `CLAIM_BLOCKED_INFLIGHT` | `mend_claims_blocked_total` |
| Lookup unavailable | `Unavailable` | backoff | — | `mend_reconcile_unavailable_total` |
| Reconciliation budget exhausted | attempts ≥ max | review queue item, campaign FAILED→ELIGIBLE awaiting human | `RECONCILIATION_ESCALATED` | `mend_reconcile_escalated_total` |
| MEND-side error (credentials, amount, NotSent, 429) | `ProviderActionOutcome` kind | deferred retry with backoff; cap → review item | `EXECUTION_DEFERRED` | `mend_executions_deferred_total` |
| DB down | exceptions | poll cycle aborts; rows unchanged; next poll resumes | — | M5 |
| Duplicate webhook | existing dedup + conditional transitions | no-op | existing | existing |
