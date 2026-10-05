# U1 — RED scenario tests (prove the bugs before fixing them)

## CONTEXT
`M2-execution-safety.md` §2 scenarios. Tests must run against real PostgreSQL (existing `AbstractIntegrationTest`) with real threads.

## CURRENT IMPLEMENTATION
See §0 (from U0).

## TASK
Write one integration test per scenario S1, S2, S3, S5, S6, S7 asserting the **target** behaviour. Run them. Those that fail today are marked `@Disabled("M2-RED: S<n> <one line>")` and committed (the suite must stay green). Those that pass today stay enabled as regression guards; note in m2-progress.md that the scenario is already safe and why.

## FILES TO TOUCH
New `backend/src/test/java/com/mend/integration/m2/ExecutionSafetyScenarioTest.java` (+ small test helpers in that package). Test-only changes to `MockPaymentProvider` are NOT allowed; use its existing knobs or a test-scoped `@Primary` `PaymentProvider` wrapper that can block on a `CountDownLatch`.

## FILES NOT TO TOUCH
All production code.

## DESIGN (per scenario)
- **Blocking provider:** test-scoped wrapper around the mock that blocks `createPaymentLink` on a latch and counts calls per attempt key.
- **Lease expiry:** never sleep for minutes. Move time by SQL: `UPDATE action_intents SET claimed_at = claimed_at - interval '10 minutes' WHERE id = ?`.
- S1: orchestrate a campaign so the intent is READY and the campaign ACTION_PENDING; thread A = scheduler poll (blocked in provider); thread B = `CampaignController.startRecovery` path (or the service call it makes). Target: provider create calls for the key == 1.
- S2: A dispatches, blocked; simulate A's death (interrupt + never finalize); run release; target: the intent is eventually resolved (assert it is NOT READY with campaign EXECUTING, i.e. not an orphan). Expected to fail today.
- S3: provider returns OutcomeUnknown; target: after a reconciliation pass the intent is not still EXECUTING with no reconciliation scheduled. Expected to fail (no reconciler exists).
- S5: A crashes before finalize; release → READY; drive the campaign to ACTION_PENDING via an allowed transition (FAILED→ACTION_PENDING); target: no second provider call for the same attempt key. Likely fails.
- S6: A blocked mid-call; a webhook-style reconciliation moves the intent; A then finalizes; target: A's result is discarded (state from reconciliation wins).
- S7: two campaigns whose original failures share an `order_id` (hand-built payloads, labelled "not a recording"); both produce link intents; target: at most one in flight.

## INVARIANTS
No production change. Suite green (count ≥ 519 + enabled new tests; Skipped = 1 + number of RED-disabled).

## TEST REQUIREMENTS
Each test: clear Given/When/Then comments, scenario id in the display name, deterministic (latches, not sleeps), < 10 s each.

## ACCEPTANCE CRITERIA
Every scenario has a test; m2-progress.md lists each as RED (disabled, with the failure message observed) or ALREADY-SAFE (with reason). At least S2 and S3 RED.

## DO NOT IMPLEMENT
Any fix.

## REVIEW CHECKLIST
Does each RED test fail for the right reason (assertion on the target behaviour, not a setup error)? Force-enable each and paste the failure line into the progress log.
