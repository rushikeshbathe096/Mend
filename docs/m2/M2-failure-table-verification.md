# M2 §9 failure table — verification (U10)

Each row of `M2-execution-safety.md` §9 mapped to the test(s) that prove its detection and recovery. All listed tests are green in the final run (`m2-final-test-run-summary.txt`).

| §9 row | Audit (as in §9) | Proving test(s) |
|---|---|---|
| Worker crash before dispatch | `CLAIM_RELEASED` | `ActionIntentTransitionsTest.releaseOnlyAppliesToUndispatchedExpiredClaims` (real DB: the release predicate — CLAIMED, `dispatched_at` null, lease expired — and dispatched work is never released); `ActionIntentClaimServiceTest.testReleaseExpiredClaims` (service level, mocked repository: audits `CLAIM_RELEASED`; fixed in U10 — the code audited `STATE_TRANSITION`), `ActionIntentClaimServiceTest.testReleaseSkipsWhenConditionalUpdateDoesNotApply` (service level: no release or audit when the conditional update does not apply) |
| Worker crash after dispatch | `EXECUTION_STUCK` | `ExecutionReconcilerTest.crashAfterDispatchIsResolvedWithinBoundedCycles`, `M2ExitCriteriaTest.crashAfterDispatchResolvesWithinBoundedCycles`, `ExecutionSafetyScenarioTest.s2CrashAfterDispatchIsResolvedByTheReconciler` |
| Provider timeout / 5xx | `PROVIDER_OUTCOME_UNKNOWN` | `ActionExecutionServiceProviderOutcomeTest.unknownLeavesIntentAndCampaignExecutingAndIsNotRecycledAsExpiredClaim`, `ActionExecutionServiceProviderOutcomeTest.providerExceptionIsUnknownNotFailed`, `M2ExitCriteriaTest.brief19` |
| Stale worker finalize | `STALE_WORKER_RESULT_DISCARDED` | `ExecutionFenceTest.lostFinalizeDiscardsResult`, `ExecutionFenceTest.webhookWinsOverWorkerMidCall`, `CorrelationTest.lateCaptureWhileWorkerMidCall` |
| Concurrent claim of 2nd action for same invoice | `CLAIM_BLOCKED_INFLIGHT` | `OneInFlightPerInvoiceTest.claimPredicateBlocksSibling` (NOT EXISTS predicate), `OneInFlightPerInvoiceTest.uniqueIndexDecidesATrueRace` (unique index under a true race, `CLAIM_BLOCKED_INFLIGHT`), `M2ExitCriteriaTest.threeWorkersOneInvoice`, `M2ExitCriteriaTest.randomizedInvoiceRaces`. Proven: the blocked intent is not claimed and stays READY (eligible for a later poll); a later successful poll is not asserted. |
| Lookup unavailable | — | `ExecutionReconcilerTest.needsReconciliationUnavailableStays` |
| Reconciliation budget exhausted | `RECONCILIATION_ESCALATED` | `ExecutionReconcilerTest.needsReconciliationNotFoundWithAttemptsExhaustedEscalates`, `ExecutionReconcilerTest.escalationFromActionPendingCampaign`, `M2ExitCriteriaTest.zeroDuplicateDebits` (`AMBIGUOUS_DEBIT` variant) |
| MEND-side error (credentials, amount, NotSent, 429) | `EXECUTION_DEFERRED` | `DeferralAndManualReviewTest.deferCodeNeverExhausts` (one case per code), `DeferralAndManualReviewTest.rateLimitedNonResendSafeActionIsNotDeferred`, `DeferralAndManualReviewTest.deferWithoutExecutingCampaignGoesToReview` |
| DB down | — | `ActionSchedulerTest.databaseDownAbortsThePollAndTheNextPollResumes` (added in U10; unit level — the poll aborts before claiming or executing and the next poll resumes; a real connection loss is not simulated) |
| Duplicate webhook | existing | `PaymentReconciliationTest.testSuccessFollowedByDuplicateWebhook`, `testFailureFollowedByDuplicateWebhook`, `testConcurrentDuplicateWebhook`, `CorrelationTest.duplicateSuccessOnRecoveredCampaignIsSilent` |

Metrics column: reserved for M5, not registered in M2 (out of scope).
