# U7 — M2 EXIT tests (brief §19, §35, §49)

## TASK
New `backend/src/test/java/com/mend/integration/m2/M2ExitCriteriaTest.java` (real PostgreSQL, real threads, latches, SQL time-travel; no sleeps > 100 ms):
1. **§19 exactly:** Worker A claims → A dispatches, provider blocked → lease expires (SQL) → release runs → Worker B polls and attempts → A's provider call returns OutcomeUnknown → reconciler runs → final state. Assert: B made zero provider calls; distinct provider resources for the attempt key == 1; final state SUCCEEDED or AWAITING_PAYMENT per stub; audit trail contains dispatch, unknown, reconciliation entries in order.
2. **Zero duplicate debits:** same as 1 but `RETRY_PAYMENT` in mock mode (synthetic debit, no idempotency): A's debit outcome Unknown → reconciler must NOT SAFE_RETRY → synthetic debit count for the intent == 1 → ends in review item `AMBIGUOUS_DEBIT`.
3. **Three workers, one invoice:** three scheduler instances (distinct worker ids) + two campaigns sharing an invoice key + the manual startRecovery path, all released by one latch. Assert: at most one in-flight intent per invoice at every observed point (poll a DB query in a 4th thread), provider create calls per attempt key ≤ 1 except permitted SAFE_RETRY resends (counted separately), distinct resources ≤ 1.
4. **Crash after dispatch:** A dispatches and dies → markStuck → reconcile → resolved within K cycles.
5. **Randomized:** repeat scenario 3 with random latch release order and random provider outcomes (seeded, seed printed) 200 times; any failure prints the seed.
All U1 RED tests must now be enabled; if any isn't, STOP.

## ACCEPTANCE CRITERIA
All pass 3 consecutive full-suite runs (flakiness check). Record the three runs' totals in m2-progress.md.

## DO NOT IMPLEMENT
Production changes. If a test reveals a bug, fix it in a separate commit "M2 U7: fix <bug>" with its own regression test, and record it.
