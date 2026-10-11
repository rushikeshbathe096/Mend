# M2 — Implementation sequence and acceptance checklist

Baseline floor: **519 run / 0 failures / 0 errors / 1 skipped** (`docs/m1/m1-final-test-run-summary.txt`, tag `m1-complete`).
One unit = one session or orchestrator step = commits prefixed `M2 Ux:`.

| Unit | Title | Depends on | Production behaviour change? |
|---|---|---|---|
| U0 | Re-verify the analysis against `main`; baseline | — | No |
| U1 | RED scenario tests (S1, S2, S3, S5, S6, S7) | U0 | No (tests only; failing ones `@Disabled("M2-RED: …")` until fixed) |
| U2 | Schema V12 + enum states + entity mapping | U1 | Migration only (new columns, CHECK, legacy EXECUTING remap) |
| U3 | `ActionIntentTransitions` + fenced executor + single claim primitive | U2 | **Yes** |
| U4 | `@Version` on ActionIntent + webhook reconciliation via transitions | U3 | **Yes** |
| U5 | Reconciler job + SAFE_RETRY + link TTL + campaign EXECUTING→ACTION_PENDING | U4 | **Yes** |
| U6 | `invoice_key` + one-in-flight unique index | U5 | **Yes** (claim blocked by index) |
| U7 | EXIT tests: §19 scenario, 3 workers, manual vs scheduler, 200× randomized | U6 | No |
| U8 | Correlation: campaign-level events never finalize intents; late original capture; single-row reference | U7 | **Yes** |
| U9 | MEND-side deferral + MANUAL_REVIEW → review queue | U8 | **Yes** |
| U10 | Close-out: query plans, docs, ADR-002 Accepted | all | No |

U1's RED tests are un-disabled by the unit that fixes them, and every one must be enabled by U7.

## Acceptance checklist (brief §49 + M2 extras)

| # | Criterion | Unit | Status |
|---|---|---|---|
| 1 | Fencing where required (`claim_token` conditional writes) | U3 | ☑ `8531b9b`, `d900140` |
| 2 | Claim expiry cannot return in-flight work to READY | U3 | ☑ `8531b9b` |
| 3 | NEEDS_RECONCILIATION implemented | U2, U5 | ☑ `fe353fd`, `65a9a2c` |
| 4 | Reconciliation determines retry safety (capability gate) | U5 | ☑ `65a9a2c`, `48dd5e4` |
| 5 | One-in-flight DB-enforced | U6 | ☑ `6468cbd`, `bf55a37` (V13 + V14) |
| 6 | Concurrency test exists (3 workers) | U7 | ☑ `3f656d6`, `0a03c66` |
| 7 | Lease-expiry-during-provider-call test exists | U7 | ☑ `3f656d6` (`M2ExitCriteriaTest.brief19`) |
| 8 | Zero duplicate debit executions in the tests (mock debit, no provider idempotency) | U7 | ☑ `3f656d6` (`zeroDuplicateDebits`) |
| 9 | Existing tests still pass, count ≥ 519, Skipped ≤ 1 (the M1 `@Disabled` is fixed in U8 → Skipped 0) | every unit | ☑ final 647 / 0 / 0 / 0 (`m2-final-test-run-summary.txt`) |
| 10 | Failure behaviour documented | U10 (§9 table, verified) | ☑ `M2-failure-table-verification.md`, `3a303bb` |
| E1 | Manual path uses the atomic claim | U3 | ☑ `8531b9b` |
| E2 | Every intent eventually leaves EXECUTING/NEEDS_RECONCILIATION/AWAITING_PAYMENT (resolved or escalated) | U5 | ☑ `65a9a2c`, `48dd5e4` |
| E3 | Links have bounded TTL | U5 | ☑ `65a9a2c` |
| E4 | M1 `@Disabled("M2: correlation …")` test enabled and green | U8 | ☑ `97b170d` |
| E5 | MEND-side errors don't exhaust campaigns | U9 | ☑ `78f7d7d`, `2ecf102` |
| E6 | MANUAL_REVIEW never reaches the executor | U9 | ☑ `78f7d7d` (no intent is created; legacy intents get NotSupported without a provider call) |
| E7 | Hot-query plans recorded (`EXPLAIN ANALYZE`, seeded data) | U10 | ☑ `M2-query-plans.md` |
