# M1 — Implementation sequence and acceptance checklist

Each unit = one coding-agent session = one PR/commit. Do not start a unit until the previous one is merged and the full suite is green. Prompts live in `agent-prompts/`.

| Unit | Title | Who | Depends on | Touches production behaviour? |
|---|---|---|---|---|
| U0 | Baseline test run + Razorpay test-mode recordings | **You** (script provided) | — | No |
| U1 | Domain port, result types, value objects | Agent | U0 baseline | No (new code only) |
| U2 | Razorpay transport + response classifier | Agent | U1 | No |
| U3 | Amount in minor units for provider requests | Agent | U1 | No (additive method) |
| U4 | Payment-link operations in `RazorpayPaymentProvider` | Agent | U0 fixtures, U2, U3 | No (not wired yet) |
| U5 | Evidence reads + `RazorpayEvidenceMapper` | Agent | U2 | No |
| U6 | Wire executor to the port; delete legacy endpoints; rewrite legacy tests; mock provider | Agent | U4, U5 | **Yes** |
| U7 | Reconciliation uses evidence mapper (`captured` only) | Agent | U5 | **Yes** |
| U8 | (Conditional) recurring debit adapter ops, unwired | Agent | U0 recurring fixtures | No |
| U9 | Close-out: results, ADR → Accepted, checklist | You + agent | all | No |

Run order: U0 → U1 → U2 → U3 → U5 → U4 → U6 → U7 → (U8) → U9. (U5 before U4 because U4's link snapshot reuses the mapper.)

After every unit, do the code review in brief §56 before merging. The prompt for each unit lists the specific things to check.

## Acceptance checklist (brief §48)

Ticked at U9 (2026-10-05). Commits are on branch `m1/u1-domain-port`; "+fix" = that unit's review-fix commit.

| # | Criterion | Satisfied by | Status |
|---|---|---|---|
| 1 | Operations verified against current official docs | `M1-provider-correctness.md` §4–§5; re-checked at U9 (§12) | ☑ docs fetched and re-checked 2026-10-05 |
| 2 | Incorrect endpoints removed/replaced | U6 `fcfd481` (+fix `45638a7`) | ☑ |
| 3 | Provider boundary clean | U1 `f0a2e75`, U6 `fcfd481`, U9 `b5077c9` (grep: no `com.mend.provider.razorpay` imports outside that package + `config`) | ☑ |
| 4 | Explicit request/response DTOs | U1 `f0a2e75`, U4 `769a2af`, U5 `cfb8b4e` | ☑ |
| 5 | Provider errors classified correctly | U2 `41b1ff7` (+fix `9a0f832`); every §9 row tested in U2/U4/U5 | ☑ |
| 6 | Ambiguous outcomes represented safely | U2 `41b1ff7` (`OutcomeUnknown`), U6 `fcfd481` (EXECUTING, not FAILED) | ☑ |
| 7 | Contract tests exist | U4 `769a2af`, U5 `cfb8b4e` (U8 skipped: no recurring fixtures) | ☑ (links + evidence reads) |
| 8 | Recorded/test-mode responses used where possible | U0 `8166d0f` (14 fixtures) + U4/U5 replay | ☑ (paid link `20` + captured payment `21` recorded; webhook fixtures `22`–`24` still open) |
| 9 | Existing tests still pass | U9: `Tests run: 516, Failures: 0, Errors: 0, Skipped: 1` ≥ 320 baseline | ☑ |
| 10 | New tests cover failure cases | U2 socket tests, U6 mapping tests (`ActionExecutionServiceProviderOutcomeTest`) | ☑ |
| 11 | No provider behaviour from memory | DOC/RECORDED cited in code comments and fixtures; doc-derived fixtures carry `_source`; `payment_link.paid` stays AMBIGUOUS | ☑ |
| 12 | ADR written | `ADR-001` → Accepted at U9 | ☑ |
| 13 | Actual test results recorded | `M1-provider-correctness.md` §12 | ☑ |

M1-specific extra gates:
- ☑ `RETRY_PAYMENT` in Razorpay mode makes zero HTTP calls (test): Phase20 scenario 3 and RazorpayTestModeEndToEndIntegrationTest step F (U6 `fcfd481`).
- ☑ No code path sets campaign `RECOVERED` from a request outcome (grep + test): only reconciliation on captured evidence (U7 `1fff235`) and the labelled mock-only synthetic path set it; Phase20 scenario 4 asserts a created link leaves the campaign EXECUTING.
- ☑ `authorized` webhook does not recover a campaign (regression test): `PaymentReconciliationTest.authorizedPaymentDoesNotRecover` (U7 `1fff235`).
- ☑ Fixture count in `src/test/resources/razorpay/recorded/` ≥ 10: 16.

Accepted deviations (owner decisions D2/D3): U6 grep gate applies to `backend/src/main` only; `PaymentExecutionResult` kept as the executor report type; MANUAL_REVIEW → NotSupported in razorpay mode.
