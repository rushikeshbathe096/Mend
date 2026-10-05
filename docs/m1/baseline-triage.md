# M1 Baseline Test Triage

Date: 2026-10-05 · Commit: `839f049` · Summary: `docs/m1/baseline-test-run-summary.txt` (raw log `docs/m1/baseline-test-run.txt` is local-only, not tracked)

## Environment

- `docker compose ps`: `mend-postgres` (postgres:17-alpine, :5433) and `mend-redis` (redis:7-alpine, :6379) were already **Up (healthy)**. Nothing was started.
- Command: `cd backend && ./mvnw test 2>&1 | tee ../docs/m1/baseline-test-run.txt`

## Results

Maven summary (copied from the raw `baseline-test-run.txt`, line 86217; the same line is in `baseline-test-run-summary.txt`):

```
[INFO] Tests run: 320, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Cross-check against `backend/target/surefire-reports/TEST-*.xml` (58 report files, summed): tests=320, failures=0, errors=0, skipped=0.

**Baseline for M1: 320 tests, 0 failures, 0 errors, 0 skipped.**

## Failing / erroring tests

None. Buckets A (environment) and B (build/config) are both empty: nothing needs a pre-M1 fix.

## Passing tests that encode known-incorrect behaviour (bucket D, not failing)

These tests pass today, but they assert behaviour that `M1-provider-correctness.md` §5 marks as INCORRECT or that breaks an AGENTS.md invariant. They are listed so that U6/M1 can expect them to change. They were not modified.

| # | Test class | Method | What it asserts | Bucket | Owner |
|---|---|---|---|---|---|
| 1 | `RazorpayPaymentProviderClientTest` | `testSuccessfulRazorpayExecution` | POST `/v1/payments/{id}/retry`; 2xx → success (endpoint does not exist, §4 #1 / §5 E2) | D | M1 U6 |
| 2 | `Phase20_RazorpayTestModeE2EValidationTest` | `testPrimaryRazorpayE2ERecovery` | Hand-authored mock of `/v1/payments/{id}/retry` | D | M1 U6 |
| 3 | `Phase20_RazorpayTestModeE2EValidationTest` | `testRazorpayIdempotencyHeaders` | Same invented endpoint | D | M1 U6 |
| 4 | `Phase20_RazorpayTestModeE2EValidationTest` | `testRazorpayDeclineHandling` | Same invented endpoint | D | M1 U6 |
| 5 | `RazorpayTestModeEndToEndIntegrationTest` | `testFullRazorpayTestModeRecoveryLoop` | Same invented endpoint | D | M1 U6 |
| 6 | `RazorpayPaymentProviderClientTest` | `testProviderTimeout` | A POST timeout returns `ERROR` (invariant 4 says `OutcomeUnknown`) | D | M1 (provider result types; see the unit prompt that owns timeout semantics) |
| 7 | `ActionExecutionServiceTest` ("Action Execution Service Integration Tests") | `testProviderErrorExecution` | A simulated HTTP 504 moves the ActionIntent to `FAILED` and the Campaign to `FAILED` (invariant 4 says unknown, not failed) | D | Split: the provider result is M1. Intent/campaign state handling for unknown outcomes (`NEEDS_RECONCILIATION`) is **M2**. |

None of the 320 tests touch claim/lease logic or the AI service in a way that fails, so M2 and M3 have no failing tests at baseline.
