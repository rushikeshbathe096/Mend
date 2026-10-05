# U3 — ActionIntentTransitions, fenced executor, single claim primitive

**Production behaviour change. Read M2-execution-safety.md §4–§5 first.**

## TASK
1. New `service/ActionIntentTransitions.java` (or `domain/repository` custom repo + thin service) — the ONLY component that changes an intent's status after creation from now on (except creation itself and `processExpiredIntents`, which you also move onto it). Each method = one conditional `@Modifying` UPDATE returning `boolean` (rows == 1):
   - `claim(id, worker, token, now)` — existing `claimIntentAtomic` semantics (READY + campaign ACTION_PENDING).
   - `releaseIfUndispatched(id, threshold)` — `WHERE status='CLAIMED' AND dispatched_at IS NULL AND claimed_at < :threshold` → READY, null token/worker.
   - `dispatch(id, token, now)` — `WHERE status='CLAIMED' AND claim_token=:token` → EXECUTING, `dispatched_at=now`, `execution_attempts = execution_attempts + 1`.
   - `finalizeFromExecuting(id, token, newStatus, responseRef, completedAt, reconcileAfter)` — `WHERE status='EXECUTING' AND claim_token=:token`; allowed targets: SUCCEEDED (mock synthetic only), FAILED, AWAITING_PAYMENT, NEEDS_RECONCILIATION.
   - `cancelNonTerminal(id, reason)` — for campaign cancellation.
2. `releaseExpiredClaims` uses `releaseIfUndispatched` per candidate; audit only when applied.
3. `ActionExecutionService`:
   - `prepareExecution`: keep every existing check byte-for-byte; as its LAST step (same tx) call `dispatch(intentId, intent.claimToken, now)`. Not applied ⇒ halt (no provider call), audit `CLAIM_LOST_BEFORE_DISPATCH`.
   - Pass the claim token from prepare to finalize. Finalize maps outcomes as M1 did EXCEPT: LinkIssued → AWAITING_PAYMENT (+responseRef, `reconcile_after = now + base`); Unknown → NEEDS_RECONCILIATION (`reconcile_after = now + grace`). If `finalizeFromExecuting` not applied ⇒ discard result, audit `STALE_WORKER_RESULT_DISCARDED` (ids, outcome kind, provider ref if any), do NOT touch campaign or campaign_attempts.
   - Campaign transitions and `campaign_attempts` writes happen only when the fenced intent update applied, in the same tx.
4. `ActionIntentClaimService.claimSpecificIntent(intentId, workerId)` — atomic single-intent claim (same predicate). `CampaignController.startRecovery` uses it; if not claimed, it does NOT execute (the scheduler owns it) and reports the "orchestration initiated" message.
5. Un-disable the U1 RED tests this fixes (expected: S1, S6, and the release part of S2/S5).

## FILES TO TOUCH
New transitions class (+ repository methods), `ActionIntentClaimService`, `ActionExecutionService`, `CampaignController` (startRecovery only), audit type names, tests.

## FILES NOT TO TOUCH
Webhook reconciliation (U4), reconciler (U5), migrations, provider package, AI.

## INVARIANTS
Compliance re-check and dispatch fence are in the same transaction (6.4). No provider call without an applied `dispatch`. No status write without a fence predicate.

## FAILURE SEMANTICS
Stale before dispatch → no call. Stale after call → result discarded, audit, reconciler/other owner resolves.

## TEST REQUIREMENTS
Per transition: applied + not-applied (wrong token, wrong status, dispatched). Executor: dispatch lost → zero provider calls; finalize lost → discard audit, campaign unchanged. Controller: concurrent scheduler claim → exactly one provider call. Grep: no `actionIntent.setStatus(` followed by `save` in services outside creation/transitions (list exceptions with reason).

## ACCEPTANCE CRITERIA
Suite green (count ≥ previous); targeted RED tests enabled and green.

## DO NOT IMPLEMENT
@Version, reconciler, SAFE_RETRY, unique index, campaign state machine changes.

## REVIEW CHECKLIST
Is there any code path that calls the provider without an applied dispatch? Any status write without token/status predicate? Does `prepareExecution` diff show only the appended dispatch call?
