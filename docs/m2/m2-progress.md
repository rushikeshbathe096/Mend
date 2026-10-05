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
