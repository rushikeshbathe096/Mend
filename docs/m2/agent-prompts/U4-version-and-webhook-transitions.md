# U4 — @Version on ActionIntent; webhook reconciliation via transitions

## TASK
1. `@Version private Long version;` on `ActionIntent` (column added in V12).
2. Add to `ActionIntentTransitions`: `recordEvidence(id, expectedStatuses, newStatus, responseRef, completedAt)` — conditional on `status IN (:expected)` (no token: webhooks are not workers). Allowed: {EXECUTING, AWAITING_PAYMENT, NEEDS_RECONCILIATION} → SUCCEEDED / FAILED (FAILED only for non-link intents per M1 D1).
3. `DefaultPaymentReconciliationService` uses `recordEvidence` instead of `setStatus+save`. Not applied ⇒ `ReconciliationResult.alreadyFinalized` (or a new `staleOrConcurrent`) + INFO; campaign untouched.
4. Find every remaining `ActionIntent` entity save after creation (cancellation in `ActionIntentService.cancelPendingIntentsForCampaign`, review flows, controller). Route status changes through transitions; anything that still saves the entity now gets optimistic-lock protection — handle `ObjectOptimisticLockingFailureException` explicitly where a retry or no-op is correct, otherwise let it surface.

## FILES TO TOUCH
`ActionIntent`, `ActionIntentTransitions`, `DefaultPaymentReconciliationService`, `ActionIntentService` (cancel), any other saver found, tests.

## FILES NOT TO TOUCH
Executor fencing logic from U3 (only adapt if compile requires), reconciler, migrations.

## TEST REQUIREMENTS
Webhook CAPTURED while worker blocked mid-call → webhook wins, worker result discarded (S6 variant). Duplicate captured webhooks → second is a no-op. Concurrent entity save → optimistic lock surfaces, no silent overwrite. All existing `PaymentReconciliationTest` cases still green.

## STOP CONDITION
If adding `@Version` breaks > 10 existing tests in ways that need production refactors outside FILES TO TOUCH, stop and report the list.

## DO NOT IMPLEMENT
Reconciler, SAFE_RETRY, unique index, correlation redesign (U8).
