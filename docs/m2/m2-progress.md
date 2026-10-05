# M2 progress log

Orchestrated run started 2026-10-05 on branch `m2/execution-safety` (from `main` @ `27658bb`, which contains the M2 design pack).
Baseline floor: **519 run / 0 failures / 0 errors / 1 skipped** (`docs/m2/m2-baseline-test-run-summary.txt`; raw log local-only).

## U0 — Verify the analysis on main; baseline
Built: `docs/m2/M2-execution-safety.md` §0 (evidence + verdict for every §1 row, checks (a)–(h), S1–S11); `docs/m2/m2-baseline-test-run-summary.txt`; this log. No code or test changes.
Tests: `Tests run: 519, Failures: 0, Errors: 0, Skipped: 1`, BUILD SUCCESS (78 classes) — matches the expected baseline.
Findings:
- CONFIRMED: (a) intent stays CLAIMED during the provider call; (b) finalize has no ownership check (only `isTerminal()`); (c) start-recovery claims via plain `save`; (d) no `@Version` on ActionIntent; (e) highest migration V11 → M2 uses V12; new columns follow V8's `TIMESTAMP WITH TIME ZONE`; (f) EXECUTING set at ActionExecutionService:292/:302; (h) ProviderCapabilities has only `supportsRecurringDebit()`.
- (g) MockPaymentProvider dedups by `computeIfAbsent` (returns Accepted(existing)), not fixture 02's 400→lookup path; observable result matches.
- CORRECTIONS (no design impact): S8/S9 only bite campaigns whose `paymentId` is a raw `pay_` id (manual start-recovery, POST /campaigns, dev seed) because strategy 4 compares the composite `payment.failed_pay_x` against the raw id; S11's mechanism is compliance blocking MANUAL_REVIEW (no intent, approval fails), not an executable MANUAL_REVIEW intent.
- EXTRA: S6 has a concrete path today — release can flip a CLAIMED intent to READY mid-call and finalize overwrites it.
Hard stop check: no finding invalidates a design decision.
Review (fresh subagent; inputs AGENTS.md, U0 prompt, §4–§7, diff): 0 blockers. Fixed in `M2 U0: review fixes` (§0 only):
- MAJOR S9: added what happens today to a real-webhook late capture (unmatched, `DefaultPaymentReconciliationService.java:81` → campaign not RECOVERED, link stays live, double-pay risk) and named the owner (U8: V14 `provider_payment_id` + late-capture handling). Design-impact line reworded.
- MAJOR "V14" undefined: §0 now says V14 is U8's migration and states the ordering rule (V14 and "campaign-level events never finalize an intent" land together in U8).
- MINOR S6: clarified the release is the root harm (closed by `dispatched_at`), the finalize write is a second symptom; S6b asserts "no write by an actor that lost the claim".
- MINOR S11: campaign stays ELIGIBLE (`RecoveryOrchestratorService.java:204-209`); noted for U9 that approval creates a new intent which OD-1 must make a link.
- MINOR evidence: added lines for the worker check (:123), non-executable set (:149-151), compliance re-check (:154-167), `failIntentAndCampaign` (:330), dispatcher `CreatePaymentLink` (:84), and the `NO_CREDENTIALS_FOR_MERCHANT` → `Rejected(AUTHENTICATION)` mapping (`RazorpayPaymentProvider.java:81`).
- MINOR vocabulary: verdicts now CONFIRMED / CHANGED-BY-M1 / WRONG only.
- NIT (e): TIMESTAMPTZ marked as a recommendation.
Rejected: #8 "U1 adaptations don't belong in U0's entry" — the orchestrator rules require writing the next unit's adaptations in the progress log before applying them; the S7 payload is an inline, labelled test payload (M1 practice for hand-built payloads), not a fixture file, so the `doc-derived/` rule does not apply. #10 raw-log hash — not needed (summary is the tracked evidence by owner decision D5).

### Adaptations for U1 (written before applying)
1. Blocking provider: a test-scoped `@Primary PaymentProvider` wrapper (in `integration/m2`) delegating to the context's `MockPaymentProvider`; it blocks `createPaymentLink` only for attempt keys the test registers (so leftover intents from other test classes sharing the DB never block), and counts create calls per key. Imported via `@TestConfiguration` into the scenario test class only.
2. S1 drives the real `CampaignController.startRecovery` (merchant via `X-Merchant-Id`), with `RecoveryOrchestratorService` as a `@MockitoBean` returning the intent that the scheduler (thread A) has already claimed and is blocked on. This keeps S1 meaningful after U3 changes the controller to the atomic claim (a test that copied the controller's lines would stay red forever).
3. S6 is split: S6a webhook moves the intent to terminal while A is blocked (expected ALREADY-SAFE thanks to the terminal check); S6b release flips the CLAIMED intent to READY mid-call and A then finalizes (the U0-found path; expected RED).
4. Target-state assertions use status *names* (e.g. `"NEEDS_RECONCILIATION"`) so the tests compile before U2 adds the enum values.
5. S7 has no `invoice_key` yet: the test defines "same invoice" as its own two campaigns whose original failure payloads share an `order_id` (hand-built, labelled "not a recording") and asserts ≤ 1 of the two intents is in an in-flight status.

## U1 — RED scenario tests
Built: `backend/src/test/java/com/mend/integration/m2/ExecutionSafetyScenarioTest.java` (7 tests) and `BlockingPaymentProvider` (test-scoped `@Primary` wrapper around the mock: counts create calls per attempt key, holds the first call for registered keys). No production change.
Results against current code (force-run, failure line observed):

| Scenario | Status | Observed failure (today) |
|---|---|---|
| S1 manual vs scheduler | **RED** | `expected: <1> but was: <2>` (two create calls for one attempt key) |
| S2 crash after dispatch | **RED** | `intent orphaned: READY with campaign EXECUTING` |
| S3 unknown outcome | **RED** | `expected: <NEEDS_RECONCILIATION> but was: <EXECUTING>` |
| S5 stale READY re-claimed | **RED** | `expected: <1> but was: <2>` |
| S6a webhook moves intent to terminal mid-call | **ALREADY-SAFE** | passes: finalize skips terminal intents (`ActionExecutionService.java:209`) |
| S6b release mid-call, then stale finalize | **RED** | `stale worker wrote EXECUTING to an intent whose claim had been released` |
| S7 two campaigns, one order | **RED** | `in-flight intents for one invoice: 2` |

RED tests are `@Disabled("M2-RED: S<n> ...")`. Full suite: `Tests run: 526, Failures: 0, Errors: 0, Skipped: 7` (519 + 7; skipped = M1 tracking test + 6 RED). Each test < 1 s.
Notes: S1 also logs a `campaign_attempts` unique-key violation from the second finalize (the existing constraint catches the duplicate attempt row, not the duplicate provider call).

Review (fresh subagent): 0 blockers. Fixed in `M2 U1: review fixes` (test-only):
- MAJOR S7 could pass vacuously once the index exists (a rolled-back claim batch leaves 0 in flight) → now `assertEquals(1, inFlight)` and the other intent must still be READY.
- MAJOR S7 fixture bypasses U6's invoice_key derivation → recorded in the `@Disabled` reason and as a U6 adaptation (the fixture must get `invoice_key` via the production derivation).
- MAJOR S6b depended on unspecified token behaviour → records the status right after release; only if release produced READY must A's later write be absent (status still READY, response reference unchanged).
- MAJOR S3 could be red for the wrong reason → precondition `responseReference == null` proves the outcome was Unknown, not LinkIssued.
- MINOR release worker A in `finally`; counts captured before joining A; A's exceptions ignored on join; fixtures scheduled near EPOCH so leftovers from other classes can never push them out of the 50-intent batch.
- NIT latch read race in `BlockingPaymentProvider`; teardown now shuts the pool down before resetting the mock.
Re-verified by force-running: all six RED tests still fail on their target assertions (S3 passes its new precondition); S6a passes.
Noted, not changed: S2 asserts "not orphaned" only; the eventual-resolution assertion (stuck EXECUTING → NEEDS_RECONCILIATION) is added in U5 (adaptation). S2 releases A after capturing the orphan state instead of "never finalizing" (deviation; A's later finalize does not affect the assertion). Scheduled polling is off in the test profile (`mend.scheduler.scheduled-enabled=false`), so no background poll races these tests.

### Adaptations for U2 (written before applying)
1. New columns use `TIMESTAMP WITH TIME ZONE` (V8 convention for scheduler columns; JPA `Instant`).
2. `version BIGINT NOT NULL DEFAULT 0` is added in V12 but NOT mapped in Java until U4 (`ddl-auto=validate` ignores unmapped columns).
3. Migration test: a throwaway schema in the test database migrated with Flyway `target=11`, seeded with legacy EXECUTING rows (with and without a `plink_` reference) and an unknown status, then migrated to 12; asserts the remap, the CHECK constraints and the reconcile index; the schema is dropped afterwards. This exercises the real V12 file on a real pre-V12 schema rather than re-implementing its SQL.
4. Pre-check done: the shared test DB is at V11 with 0 `action_intents` rows, so V12 cannot fail on existing data there.
5. `ActionIntentStatus.isInFlight()` = CLAIMED, EXECUTING, NEEDS_RECONCILIATION, AWAITING_PAYMENT; the two new states are non-terminal and non-executable (existing predicates unchanged).
