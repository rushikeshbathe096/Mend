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

| # | Criterion | Satisfied by | Status |
|---|---|---|---|
| 1 | Operations verified against current official docs | `M1-provider-correctness.md` §4–§5 | ☑ (docs) / ☐ re-check date at U9 |
| 2 | Incorrect endpoints removed/replaced | U6 | ☐ |
| 3 | Provider boundary clean | U1, U6 (grep: no `razorpay` imports outside `com.mend.provider.razorpay` + config) | ☐ |
| 4 | Explicit request/response DTOs | U1, U4, U5 | ☐ |
| 5 | Provider errors classified correctly | U2 (+ every §9 row tested) | ☐ |
| 6 | Ambiguous outcomes represented safely | U2 (`OutcomeUnknown`), U6 (EXECUTING, not FAILED) | ☐ |
| 7 | Contract tests exist | U4, U5, U8 | ☐ |
| 8 | Recorded/test-mode responses used where possible | U0 + U4/U5 replay | ☐ |
| 9 | Existing tests still pass | U9 (count ≥ baseline from U0) | ☐ |
| 10 | New tests cover failure cases | U2 socket tests, U6 mapping tests | ☐ |
| 11 | No provider behaviour from memory | every classification cites DOC or RECORDED; AMBIGUOUS rows isolated | ☐ |
| 12 | ADR written | `ADR-001` → Accepted at U9 | ☐ |
| 13 | Actual test results recorded | U9 fills `M1-provider-correctness.md` §12 | ☐ |

M1-specific extra gates:
- ☐ `RETRY_PAYMENT` in Razorpay mode makes zero HTTP calls (test).
- ☐ No code path sets campaign `RECOVERED` from a request outcome (grep + test).
- ☐ `authorized` webhook does not recover a campaign (regression test).
- ☐ Fixture count in `src/test/resources/razorpay/recorded/` ≥ 10.
