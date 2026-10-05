# U0 — Re-verify the M2 analysis against current main; baseline

## CONTEXT
`docs/m2/M2-execution-safety.md` was written from the pre-M1 snapshot plus M1 logs. M1 changed `ActionExecutionService` and `DefaultPaymentReconciliationService`. Claim, scheduler and lease code is believed unchanged.

## TASK
No code changes. Verify and correct the analysis.
1. `cd backend && ./mvnw test` → write `docs/m2/m2-baseline-test-run-summary.txt` (command, date, commit, totals line, per-class totals). Expect 519/0/0/1.
2. For every row of §1 and every scenario S1–S11 in §2, open the current code and record `file:line` evidence, plus CONFIRMED / CHANGED-BY-M1 / WRONG. Write into a new section "§0 Verification on main (<commit>)" at the top of `M2-execution-safety.md`. Do NOT rewrite §1–§9; add corrections in §0.
3. Specifically confirm: (a) `prepareExecution` leaves the intent CLAIMED during the provider call; (b) `finalizeExecution` has no ownership check; (c) `CampaignController.startRecovery` claims via plain save; (d) `ActionIntent` has no `@Version`; (e) the highest Flyway migration number (expected V11 → M2 uses V12); (f) where M1 sets EXECUTING for LinkIssued/Unknown; (g) how `MockPaymentProvider` handles a repeated `reference_id` (does it emulate fixture 02 uniqueness?); (h) `ProviderCapabilities` current methods.
4. If any finding invalidates a design decision (not just a line number), STOP and report it.

## FILES TO TOUCH
`docs/m2/M2-execution-safety.md` (§0 only), new `docs/m2/m2-baseline-test-run-summary.txt`, new `docs/m2/m2-progress.md`.

## FILES NOT TO TOUCH
All code, all tests, ADR-002 decisions.

## ACCEPTANCE CRITERIA
§0 exists with evidence for every row; baseline summary written; progress log started.

## DO NOT IMPLEMENT
Anything.
