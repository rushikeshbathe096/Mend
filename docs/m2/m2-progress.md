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

## U2 — Schema V12, new states, entity mapping
Built: `V12__execution_safety.sql` (columns `dispatched_at`, `reconcile_after` as TIMESTAMPTZ; `reconcile_attempts`, `execution_attempts` INT NOT NULL DEFAULT 0; `invoice_key` VARCHAR(120); `version` BIGINT NOT NULL DEFAULT 0 (unmapped until U4); legacy EXECUTING remap — `plink_` reference → AWAITING_PAYMENT, other → NEEDS_RECONCILIATION, both with `reconcile_after = now()`; CHECK on status values and attempt counters; partial index `idx_action_intents_reconcile_due`; header runbook). `ActionIntentStatus`: NEEDS_RECONCILIATION, AWAITING_PAYMENT, `isInFlight()`. `ActionIntent`: `dispatchedAt`, `reconcileAfter`, `reconcileAttempts`, `executionAttempts`, `invoiceKey`.
Tests: `V12MigrationTest` (2: real V12 on a throwaway schema migrated to V11 and seeded — remap of plink/other/pay EXECUTING rows, untouched READY/SUCCEEDED, defaults; CHECK rejects unknown status and negative counters; reconcile index exists; schema dropped), `ActionIntentStateMachineTest.testM2StatusPredicates` (1).
Full suite: `Tests run: 529, Failures: 0, Errors: 0, Skipped: 7` (526 + 3). Context starts with `ddl-auto=validate`; shared DB now at V12; no leftover test schemas.
Deviations: none.
Review (fresh subagent): 0 blockers.
- MAJOR legacy remap leaves `dispatched_at` NULL and `execution_attempts` 0 on rows M1 had dispatched once (breaks §7's grace/budget arithmetic). **Not fixed by editing V12**: V12 is committed and applied to the shared DB, and AGENTS invariant 10 forbids editing an existing migration. Instead (adaptations, recorded below): U5's reconciler treats a NULL `dispatched_at` on NEEDS_RECONCILIATION/AWAITING_PAYMENT as "dispatched, time unknown" (grace satisfied via `COALESCE(dispatched_at, claimed_at, created_at)`), and U6's V13 backfills `dispatched_at = COALESCE(claimed_at, now())`, `execution_attempts = GREATEST(execution_attempts, 1)` for such rows. Real impact today is nil (0 intent rows existed when V12 ran here; no other environment has V12).
- MINOR remap writes no audit rows (invariant 9). Rejected for SQL: `audit_logs` is hash-chained (`previous_hash`/`record_hash` computed by `AuditService`), so SQL-inserted rows would break chain verification. The migration header + `flyway_schema_history` are the trail for this one-time remap; to be listed as an explicit exception in ADR-002 Evidence at U10.
- MINOR CHECK/INDEX take locks inside Flyway's transaction. Acceptable at current size; cannot edit V12's header (checksum). The lock note goes into V13's header (U6) and the U10 docs.
- NIT tests: fixed in `M2 U2: review fixes` — `reconcile_after` asserted on every remapped row; a `plinkX...` seed proves the LIKE escape matches `_` literally. `V12MigrationTest`: `Tests run: 2, Failures: 0`.
- NIT trivial name-length assertion: kept (harmless).

### Adaptations for U3 (written before applying)
1. `ActionIntentTransitions` is a `@Service` over new `@Modifying` JPQL updates in `ActionIntentRepository`; methods join the caller's transaction (claim service `REQUIRES_NEW`, executor prepare/finalize transactions). Besides the listed methods it also gets `promote(id, now)` (SCHEDULED→READY) and `expire(id, now)` (SCHEDULED/READY/PENDING past `expires_at` → EXPIRED) so no service writes status via `setStatus + save` after creation. `cancelNonTerminal` is added but wired into cancellation in U4 (U4 owns `ActionIntentService.cancelPendingIntentsForCampaign`).
2. `dispatch` requires a non-null `claim_token`. Every CLAIMED intent produced by production code has one (claim primitive). Existing tests that hand-build CLAIMED intents without a token get a token in their fixture (fixture correction, not an assertion change), noted per file.
3. Finalize targets: SyntheticCaptured → SUCCEEDED (mock only); LinkIssued → AWAITING_PAYMENT + response ref, `reconcile_after = now + mend.reconciler.backoff-base-seconds` (30); Unknown → NEEDS_RECONCILIATION, `reconcile_after = now + mend.reconciler.not-found-grace-seconds` (120); Rejected/NotSent/NotSupported → FAILED (existing campaign failure path). Property names are reused by U5, which adds them to `application.properties`.
4. The fenced intent update is applied first in the finalize transaction; `campaign_attempts` and campaign transitions are written only if it applied. Not applied → audit `STALE_WORKER_RESULT_DISCARDED` (ids, outcome kind, provider ref) and the report is still returned to the caller (informational).
5. `claimSpecificIntent(intentId, workerId)` uses the same claim predicate; the controller executes only if it applied, otherwise it returns the existing "orchestration initiated" message.
6. RED tests are enabled by whichever unit makes them pass. Expected at U3: S1, S6b, S2 (release no longer orphans), S5 (released-and-reclaimed path closed), and S3 (Unknown → NEEDS_RECONCILIATION is part of U3's finalize mapping). S2's eventual-resolution assertion is added in U5.
8. DemoController's demo claim (`claimIntent`, plain save of CLAIMED) is a second bypass of the claim primitive; it switches to `claimSpecificIntent` too (Requirement 6 "all paths into execution use the same claim primitive"), although the file is not listed under U3's FILES TO TOUCH.
9. The executor's two pre-dispatch status writes (compliance blocked → FAILED; execution window passed → EXPIRED) become `abandonClaim(id, token, FAILED|EXPIRED)`; the checks themselves stay byte-for-byte. A lost dispatch throws inside prepare so the whole prepare transaction (incl. the campaign ACTION_PENDING→EXECUTING transition) rolls back; `CLAIM_LOST_BEFORE_DISPATCH` is audited in a fresh transaction.
7. Existing assertions that change (each with `// M2 behaviour change: ..., see ADR-002`): LinkIssued EXECUTING → AWAITING_PAYMENT; Unknown EXECUTING → NEEDS_RECONCILIATION; the scheduler-transaction test's "claim committed during the provider call" now observes the committed dispatch (EXECUTING), which is the point of the fence.

## U3 — Fenced transitions, fenced executor, single claim primitive (production behaviour change)
Built:
- `service/ActionIntentTransitions` + `@Modifying` JPQL updates in `ActionIntentRepository`: `claim`, `promote`, `expire`, `releaseIfUndispatched`, `dispatch`, `abandonClaim`, `finalizeFromExecuting`, `cancelNonTerminal`. Each is one conditional UPDATE (status, and `claim_token` for worker-owned writes) returning `applied`.
- `ActionIntentClaimService`: promote/claim/release/expire go through transitions; release applies only to undispatched claims; new `claimSpecificIntent(intentId, workerId)` (`REQUIRES_NEW`, same predicate as the scheduler).
- `ActionExecutionService`: prepare keeps every check; the two pre-dispatch status writes (compliance blocked, window expired) use `abandonClaim`; dispatch is the last step of prepare in the same transaction as the compliance re-check — not applied ⇒ the prepare transaction rolls back (incl. the campaign transition), `CLAIM_LOST_BEFORE_DISPATCH` audited, no provider call. Finalize applies the fenced update first (SyntheticCaptured → SUCCEEDED, LinkIssued → AWAITING_PAYMENT + ref + `reconcile_after = now+30s`, Unknown → NEEDS_RECONCILIATION + `reconcile_after = now+120s`, Rejected/NotSent/NotSupported → FAILED) and writes `campaign_attempts` + campaign transitions only if it applied; otherwise `STALE_WORKER_RESULT_DISCARDED` (ids, outcome kind, provider ref, current status).
- New `executeActionIntent(intentId, workerId, claimToken)`; scheduler, manual start-recovery and demo pass the token from their claim. The 2-arg form is `@Deprecated` (reads the stored token) and has no production caller.
- `CampaignController.startRecovery` and `DemoController` claim via `claimSpecificIntent`; the manual path executes only when the claim applied.
Tests: new `ActionIntentTransitionsTest` (6), `ExecutionFenceTest` (2: lost dispatch → 0 provider calls + campaign rollback + audit; lost finalize → discarded + audit + no attempt row + campaign unchanged), `CampaignControllerTest` +2 (executes only after atomic claim; never executes when the claim is refused), `ActionIntentClaimServiceTest` +1 (dispatched intent never released). RED tests enabled: **S1, S2, S3, S5, S6b** (all green). S7 still RED (U6).
Changed assertions (all commented `// M2 behaviour change ... see ADR-002`): Unknown → NEEDS_RECONCILIATION (Phase10 ×2, ActionExecutionServiceTest, ActionSchedulerTransactionIntegrationTest, ActionExecutionServiceProviderOutcomeTest ×2); LinkIssued → AWAITING_PAYMENT (ActionExecutionServiceProviderOutcomeTest, Phase20); claim-service unit test verifies the conditional updates instead of `save`; scheduler unit test stubs/verifies the 3-arg executor call; scheduler-transaction observer sees the committed dispatch (EXECUTING + `dispatched_at`) during the provider call. Fixture corrections (no assertion change): CLAIMED intents built by hand in ActionExecutionServiceProviderOutcomeTest, Phase20 (×2) and Phase19 now carry a claim token.
Full suite: `Tests run: 540, Failures: 0, Errors: 0, Skipped: 2` (529 + 11; skipped = M1 tracking test + S7 RED).
Grep (status writes outside transitions/creation): `ActionIntentClaimService` :58/:81/:108/:139 only mirror an applied conditional update onto the in-memory object (no save); remaining entity saves: `ActionIntentService:158` (cancellation) and `DefaultPaymentReconciliationService:142/:183` (webhooks) — both U4.
Deviations: (1) found during U3: the executor first read the claim token from the DB at prepare time, which would let a worker adopt a new owner's token; fixed by passing the claimer's token (3-arg method) — `ExecutionFenceTest.lostDispatchMakesNoProviderCall` caught it. (2) DemoController changed (adaptation 8). (3) prepare's expiry write moved to `abandonClaim` (adaptation 9); the check itself is unchanged.
Review (fresh subagent): 0 blockers. Fixed in `M2 U3: review fixes`:
- MAJOR compliance-block and in-prepare expiry writes used the token read back from the DB (fenced on status only) → both now use the caller's token; a stale worker cannot FAIL/EXPIRE another owner's claim. Tests: `staleWorkerCannotExpireAnotherOwnersClaim`, `staleWorkerCannotFailAnotherOwnersClaimOnComplianceBlock`.
- MAJOR a null caller token fell back to the stored token (fail open), and the deprecated 2-arg executor used it → the 2-arg overload is **removed**; `executeActionIntent(id, worker, token)` fails closed on a null token (`executorRequiresClaimToken`). All test callers pass their fixture's token (ActionExecutionServiceTest ×10, ActionExecutionServiceProviderOutcomeTest, Phase19, Phase20 ×3, Phase10 ×2 with a fixture token added); compilation proves there is no remaining 2-arg caller in `src/main` or `src/test`.
- MINOR ACTION_EXPIRED audited even when the expiry write did not apply → audited only when applied; otherwise halt "claim no longer held".
- MINOR detached entities after `clearAutomatically` → finalize reloads the campaign after the fenced update and keeps the managed copy from `save`; the discard audit reads the current status freshly; prepare's returned objects are documented as id-only.
- MINOR token matchers used `any()` → scheduler and controller tests assert the exact claim token (`eq("claim-key-4")`, `eq("claim-exception-4")`, `eq("claim-token-manual")`).
- MINOR grep evidence for remaining status writers → recorded above (cancellation and webhooks owned by U4).
- MINOR undeclared narrower window expiry → declared here: `expireUnclaimed` only expires SCHEDULED/READY/PENDING; a CLAIMED intent past its window is expired by prepare's (token-fenced) check instead.
- NIT `ExecutionPreparation` token now a constructor argument; observer field renamed `dispatchWasCommitted`.
Not changed: fully-qualified names (style). Out-of-scope (demo owner): the demo's "ambiguous" scenario claims, then forces the campaign to EXECUTING without dispatching and narrates "provider request dispatched" (simulated text); it reconciles a captured webhook immediately so no orphan persists, but the narration is simulated — recorded for the demo owner, not changed in M2.
Verified: S2/S5 assertions are only "not orphaned as READY" / "no second create call"; S3 asserts NEEDS_RECONCILIATION + Unknown precondition, no reconciler behaviour.
Tests after fixes: `Tests run: 543, Failures: 0, Errors: 0, Skipped: 2`.

### Adaptations for U4 (written before applying)
1. `recordEvidence(id, expectedStatuses, newStatus, responseRef, completedAt)` is a conditional UPDATE on `status IN (:expected)` (no token). Webhook correlation can find an intent that is still CLAIMED (S6a: webhook arrives while the worker is mid-call before dispatch committed is impossible now, but a webhook can arrive while EXECUTING); expected set = {EXECUTING, AWAITING_PAYMENT, NEEDS_RECONCILIATION} per the U4 prompt. S6a (enabled) relies on EXECUTING → SUCCEEDED by webhook, which this keeps.
2. Cancellation (`ActionIntentService.cancelPendingIntentsForCampaign`) today only cancels *executable* intents (PENDING/SCHEDULED/READY). To keep that semantics race-free it uses a new conditional `cancelIfExecutable` (status IN PENDING/SCHEDULED/READY) instead of a Java check followed by `cancelNonTerminal` (which would also cancel in-flight work in a race). `cancelNonTerminal` stays for U8's late-capture cancellation.
3. `@Version` lives on `ActionIntent`; repository conditional updates also bump `version` (`version = version + 1`) so entity saves that raced a conditional update fail loudly instead of overwriting.
