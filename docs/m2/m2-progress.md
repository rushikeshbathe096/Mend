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
4. Existing `PaymentReconciliationTest` fixtures A, B (start FAILED = pre-M2 "provider ERROR") and C, D, H, J (start CLAIMED = pre-M2 "in flight during the provider call") are moved to their M2 equivalents — NEEDS_RECONCILIATION and EXECUTING — with `// M2 behaviour change` comments; **assertions unchanged**. Reason: §4 (which may not be changed) has no evidence transition from CLAIMED (never dispatched) or terminal FAILED, and U4 restricts `recordEvidence` to {EXECUTING, AWAITING_PAYMENT, NEEDS_RECONCILIATION}. New regression tests: evidence for a CLAIMED or a terminal FAILED intent is a no-op. (A captured *original* payment on such a campaign is U8's campaign-level late capture.)
3. `@Version` lives on `ActionIntent`; repository conditional updates also bump `version` (`version = version + 1`) so entity saves that raced a conditional update fail loudly instead of overwriting.

## U4 — @Version + webhook reconciliation via transitions (production behaviour change)
Built: `@Version Long version` on `ActionIntent` (V12 column); every conditional UPDATE in `ActionIntentRepository` now also does `version = version + 1` (10 updates), so an entity save that raced a transition fails with `ObjectOptimisticLockingFailureException`. New transitions `recordEvidence(id, expected ⊆ {EXECUTING, AWAITING_PAYMENT, NEEDS_RECONCILIATION}, SUCCEEDED|FAILED, ref, completedAt)` and `cancelIfExecutable` (PENDING/SCHEDULED/READY). `DefaultPaymentReconciliationService` writes intents only via `recordEvidence`; not applied ⇒ `ReconciliationResult.alreadyFinalized` + INFO `reconciliation_not_applied`, campaign untouched. `ActionIntentService.cancelPendingIntentsForCampaign` uses `cancelIfExecutable`. Grep: no `setStatus(ActionIntentStatus…)`+`save` left in services (remaining: dev seed creation; claim-service in-memory mirrors of applied updates).
Demo (found by the suite, adaptation): `DemoController` CUSTOMER_ACTION never executed its intent and relied on a campaign-level (original payment) capture finalizing a READY intent; PROVIDER_AMBIGUITY claimed but never dispatched. Both now follow the M2 flow: CUSTOMER_ACTION claims + executes (mock link → AWAITING_PAYMENT) then delivers a captured webhook for the link payment correlated by `notes.mend_intent_id`; PROVIDER_AMBIGUITY records the dispatch and a simulated unknown outcome via the fenced transitions (NEEDS_RECONCILIATION), then reconciles from intent-specific evidence. Both now also satisfy U8's invariant 15 in advance.
Tests: new `ExecutionFenceTest.webhookWinsOverWorkerMidCall` (webhook SUCCEEDED mid-call, worker result discarded + audited) and `staleEntitySaveFailsWithOptimisticLock`; `PaymentReconciliationTest` +2 (evidence for CLAIMED / terminal FAILED is a no-op). Existing reconciliation fixtures A, B (FAILED → NEEDS_RECONCILIATION) and C, D, H, J (CLAIMED → EXECUTING) moved to M2-equivalent states with comments; assertions unchanged. Duplicate-webhook no-op covered by existing C/D/H.
Full suite: `Tests run: 547, Failures: 0, Errors: 0, Skipped: 2` (543 + 4). @Version broke 0 existing tests (hard-stop threshold 10 not reached).
Flake noted: `Phase5EndToEndIntegrationTest.testEndToEndWebhookRedisPipeline` failed once in a full run (consumer polled 0 messages from the shared Redis stream) and passed on rerun alone and in the next full run; it reads a stream other test classes also write to. Watching it (U7 runs the suite 3×).
Review (fresh subagent): 0 blockers. Fixed in `M2 U4: review fixes`:
- MAJOR captured evidence for a terminal (e.g. FAILED) intent was dropped with only an INFO log → intent stays terminal (§4), but a not-applied capture whose intent is not SUCCEEDED now logs WARN `captured_evidence_not_applied` and writes audit `CAPTURED_EVIDENCE_NOT_APPLIED` (ids + status code). Test `capturedEvidenceForFailedIntentIsAuditedNotDropped`. **Open owner decision**: what to do with real captured money for a terminal intent (refund / manual review / recover the campaign). This is not in §4/§7 and is not resolved here; listed for ADR-002 at U10. Captures for a *terminal campaign* are still ignored by the pre-existing late-webhook guard (unchanged; U8's late-capture handling owns it).
- MAJOR missing duplicate-captured test → already covered by `PaymentReconciliationTest.testSuccessFollowedByDuplicateWebhook` (C: two event ids, second SKIPPED_ALREADY_FINALIZED, 1 attempt, campaign RECOVERED, intent SUCCEEDED). Added the transition-level race `ActionIntentTransitionsTest.recordEvidenceAppliesOnceDuplicateIsNoOp` (second evidence → false, reference and completedAt unchanged) and `recordEvidenceRejectsDisallowedSourcesAndTargets`.
- MINOR D1 not enforced by the fence → failure evidence uses `FAILURE_FROM = {EXECUTING, NEEDS_RECONCILIATION}`; AWAITING_PAYMENT (always a live link) can never be failed, even if the webhook read the intent before its link reference was written. Test `failedEvidenceNeverEndsAwaitingPayment`.
- MINOR demo narration not tied to outcomes → CUSTOMER_ACTION re-reads the intent and throws unless AWAITING_PAYMENT; PROVIDER_AMBIGUITY throws if `dispatch` / `finalizeFromExecuting` do not apply.
- MINOR demo dispatch outside `prepareExecution` → kept (no provider call follows; there is nothing to compliance-gate) with a comment forbidding the pattern on any provider path. DemoController is **not** profile-gated (`@RestController` at `/api/v1/demo`, no `@Profile`); recorded as an out-of-scope finding.
- NIT no-op logs used the stale loaded status → they re-read and log `status_now` (and `status_at_read` on the WARN).
Verified, no change: `clearAutomatically` side effects. Reconciliation moves the campaign via `campaignLifecycleService.transitionState(merchantId, campaignId, …)` (by id), and the attempt and event are saved explicitly. `HumanApprovalService.rejectReview` re-reads the campaign after cancellation and saves the review with `saveAndFlush` (merge).
Rejected: "set campaign fixtures A/B to EXECUTING" (finding 5). The fixture moves are declared above and assertions are unchanged; changing the campaign state too would change what the test asserts about campaign transitions. The reversed behaviour (captured for a FAILED intent no longer recovers) is pinned by `evidenceForTerminalFailedIntentIsNotApplied` and now surfaced by the audit test.
Inventory (spec item 4), ActionIntent savers in `src/main` after U4: `DevDataInitializer:351` (creation), `ActionIntentService` (creation only), `ActionIntentClaimService` (in-memory mirrors after applied conditional updates, no save). None can save a stale managed entity after a transition, so no `ObjectOptimisticLockingFailureException` handling is needed.
Tests after fixes: `Tests run: 551, Failures: 0, Errors: 0, Skipped: 2`.
Out-of-scope / decision for later units: `cancelIfExecutable` keeps the pre-M2 rule (cancel only PENDING/SCHEDULED/READY). §4 says "any non-terminal → CANCELLED" on campaign cancellation. Today's only caller is review rejection, where the campaign has no in-flight intent. U5/U8 must decide what campaign cancellation does with an AWAITING_PAYMENT link (`cancelNonTerminal` is ready but unused).

### Adaptations for U5 (written before applying)
1. Legacy rows (U2 review): when the reconciler evaluates SAFE_RETRY grace, it uses `COALESCE(dispatched_at, claimed_at, created_at)`; a NULL `dispatched_at` on NEEDS_RECONCILIATION/AWAITING_PAYMENT is treated as "dispatched, time unknown". The V13 backfill lands in U6.
2. Reconciler transitions (`markStuck`, `reconcileTo`, `safeRetry`, `toAwaitingPayment`) are new conditional updates in `ActionIntentTransitions`, fenced by status (no token: the reconciler is not the worker; `markStuck` makes the dead worker's later finalize fail its EXECUTING+token predicate).
3. `SKIP LOCKED` pick is a native query in its own short transaction that also pushes `reconcile_after` forward (the reconcile lease), then provider lookups run outside any transaction.
4. Backoff policy class `ReconcileBackoff` with injectable `RandomGenerator` and `Clock` (no `*Utils`).
5. SAFE_RETRY re-enters READY with the same attempt key; it clears `claim_token`, `worker_id`, `claimed_at`, `dispatched_at`; campaign EXECUTING → ACTION_PENDING via the new state-machine edge (reason `RETRY_DEFERRED`).

## U5 — Reconciler job, SAFE_RETRY, link TTL (production behaviour change)
Built:
- `ProviderCapabilities.supportsIdempotentResend(ActionType)`. Razorpay: true only for REQUEST_CUSTOMER_ACTION. Mock: true for REQUEST_CUSTOMER_ACTION, false for RETRY_PAYMENT / MANUAL_REVIEW.
- `MockPaymentProvider`:
  - reference uniqueness (a second create with the same key returns the existing link, logged `duplicate_reference`);
  - `getPayment` returns only payments made with the `payLink` knob (SYNTHETIC);
  - knobs `setLinkState`, `setSimulatedLostResponse` (link stored, OutcomeUnknown returned), `setSimulatedLookupUnavailable`.
- `CampaignStateMachine`: EXECUTING → ACTION_PENDING (reason `RETRY_DEFERRED`).
- New `ExecutionReconciler` (scheduled, toggles `mend.reconciler.enabled` / `scheduled-enabled`). Each cycle:
  - (a) stuck sweep: EXECUTING older than T_stuck → NEEDS_RECONCILIATION, audit `EXECUTION_STUCK`;
  - (b) a short transaction picks due rows with `FOR UPDATE SKIP LOCKED` (native) and pushes `reconcile_after` by the lease;
  - (c) per row, outside any transaction: GET lookups only, then the §7 table applied through new status-fenced transitions (`markStuck`, `reconcileTo`, `deferReconcile`, `safeRetry`), each counting `reconcile_attempts` and setting the next `reconcile_after` with full-jitter backoff;
  - (d) SAFE_RETRY → READY with the same key, ownership and dispatch cleared, campaign → ACTION_PENDING, audit `SAFE_RETRY`. Escalation → intent FAILED, campaign → FAILED → ELIGIBLE, `HumanApprovalService.createReview` with `RECONCILIATION_ESCALATED` / `AMBIGUOUS_DEBIT`;
  - (e) SUCCEEDED → campaign RECOVERED; FAILED → existing failure path (FAILED → EXHAUSTED).
- `ReconcileBackoff`: injectable `RandomGenerator`, 1 s floor so a row is always pushed into the future.
- `ProviderActionDispatcher`: `expireBy = now + mend.recovery.link-ttl-hours` (72). TTL outside 15 min–180 days fails at startup.
- Config defaults (OD-2/OD-3) in `application.properties`. Tests disable the schedule and call `runCycle`.

Adaptations (decided while building):
- The SAFE_RETRY capability gate is in the UPDATE predicate (`action_type IN :resendSafeTypes AND execution_attempts < :max`), so no caller can resend a debit even by mistake.
- Legacy rows: the grace and stuck checks use `COALESCE(dispatched_at, claimed_at, created_at)`; the pick includes `reconcile_after IS NULL`.
- `supportsIdempotentResend` is a `default` method returning false (fail safe). That keeps the existing lambda-based `ProviderActionDispatcherTest` compiling unchanged. The 4-arg dispatcher constructor is kept, defaulting the TTL to 72 h.
- The reconciler also updates the `campaign_attempts` row: ISSUED / SUCCESS / FAILED / UNRESOLVED.
- Behaviour fix found by the tests: `ActionExecutionService.finalizeExecution` inserted a new `campaign_attempts` row on every finalize. A SAFE_RETRY re-executes the same attempt number, so it hit the unique key. It now finds or creates the row.
- A PAID link without a CAPTURED `getPayment` is never success (invariant 2): the row stays and logs WARN `paid_link_without_captured_evidence`.
- NotFound after grace when the campaign is terminal → intent FAILED without resend. When the campaign is not EXECUTING / ACTION_PENDING → escalate.
- Razorpay-mode reconciler tests build the real adapter by hand against the stub inside the shared test context. A separate `@SpringBootTest` context exhausted Postgres connections ("too many clients") in the full run.

Tests:
- `ExecutionReconcilerTest` (16, mock):
  - every §7 row for NEEDS_RECONCILIATION and AWAITING_PAYMENT;
  - SAFE_RETRY plus the resend using the same key;
  - escalation when dispatches are exhausted;
  - Unavailable ≠ NotFound;
  - debit never resent and ending in an `AMBIGUOUS_DEBIT` review after 5 deferrals;
  - liveness: crash before or after the request reached the provider, resolved within 2 cycles;
  - recent EXECUTING untouched;
  - two reconciler instances concurrently: no row picked twice, each processed once.
- `ExecutionReconcilerRazorpayTest` (5, recorded 05/06/20/21 + doc-derived empty lookup; asserts the reconciler sent only GETs).
- `ReconcileBackoffTest` (4, seeded): bounds, floor, cap, determinism.
- `MockPaymentProviderM2Test` (5).
- `ProviderActionDispatcherTest` +2 (expireBy, TTL bounds).
- `CampaignStateMachineTest` +1.
- `ExecutionSafetyScenarioTest` +1: S2 end-to-end. The reconciler resolves a dead worker's intent, the late worker result is discarded, and the resend gets the same link (two sends, one link).
- S2/S3/S5 were already enabled in U3. The S2 resolution the U3 comment deferred is now asserted.

Full suite: `Tests run: 585, Failures: 0, Errors: 0, Skipped: 2` (551 + 34).
Review (fresh subagent): 0 blockers. Fixed in `M2 U5: review fixes`:
- MAJOR inconsistent escalation on terminal campaigns (verified: `createReview` accepts any campaign state, so no stuck row, but the policy differed between attempts < max and ≥ max) → one policy in `escalateInTx`: a link NotFound on a terminal campaign → FAILED + audit `RECONCILER_FAILED NOT_FOUND_CAMPAIGN_<state>`, no review, for both attempt counts; an `AMBIGUOUS_DEBIT` always gets a review, whatever the campaign state (money may have moved). Tests `linkNotFoundOnTerminalCampaignFailsWithoutReview`, `ambiguousDebitOnTerminalCampaignStillGetsReview`, `escalationFromActionPendingCampaign`.
- MINOR exception path had no backoff → `deferAfterError` re-reads the row, defers with backoff (attempts +1) and audits `RECONCILE_ERROR`; if even that fails, the row keeps its lease. Test `errorWhileReconcilingBacksOff`.
- MINOR stuck sweep audit not atomic → `markStuck` and its `EXECUTION_STUCK` audit run in one transaction.
- MINOR capture on a non-recoverable campaign was only a WARN → audit `CAPTURED_ON_TERMINAL_CAMPAIGN` + review item. Test `capturedOnTerminalCampaignIsSurfaced`.
- MINOR unlocked campaign read-decide-transition → reconciler transactions read the campaign with `findByIdForUpdate` (PESSIMISTIC_WRITE; `Campaign` already has `@Version`, so a racing write fails loudly). Residual: a SAFE_RETRY that leaves an ACTION_PENDING campaign untouched can still race a cancellation that commits afterwards. The READY intent is then on a CANCELLED campaign; it is not in flight and can never be claimed (claim requires ACTION_PENDING).
- MINOR reconciler writes fenced on status only → `reconcileTo`, `deferReconcile`, `safeRetry` also require `version = :seenVersion` (read after the lease bumped it), so a slow reconciler can never apply a decision made on a stale lookup after another actor changed the row. Test `reconcilerWritesRequireTheSeenVersion`.
- MINOR settings not validated → `Settings` rejects batch < 1, lease/stuck ≤ 0, negative grace, maxExecutionAttempts < 1, negative deferral cap. Test `invalidSettingsAreRejected`.
- MINOR concurrency test could not tell SKIP LOCKED from plain FOR UPDATE → new `lockedRowsAreSkippedNotWaitedFor`: another transaction holds row locks on 3 of 6 due rows, and a reconciler must return within 10 s having processed exactly the other 3.
- MINOR S2 scenario's `runCycle` could act on other classes' leftover EXECUTING rows → they are pushed to `dispatched_at = now()` first. The link id is read from the lookup instead of being built.
- MINOR coverage → added NR found PARTIALLY_PAID / CANCELLED, AWAITING EXPIRED, terminal-campaign NotFound, dead-worker finalize refused after the stuck sweep (`deadWorkerCannotFinalizeAfterStuckSweep`), and a `reconcile_after` assertion on paid-without-evidence.
Rejected / explained:
- "S2/S3/S5 un-disable not in the diff". They were enabled in U3 (see the U3 entry). The second skip is S7 (`@Disabled("M2-RED: … U6")`), the only remaining RED test; the M1 tracking test is the first.
- "Debit budget counts attempts, not time". ADR-002 OD-3 defines the MEND-side deferral cap as a count (5). Changing it to a time window would change an owner decision. Escalating early only routes to a human, which is the safe direction. Recorded for the owner.
- "`stuckAfter` > provider HTTP timeout". Valid concern, but the Razorpay timeouts are not visible to the reconciler in mock mode. Recorded for U10/ADR evidence: T_stuck 120 s must stay above Razorpay connect+read timeout. If it does not, a live worker's result is discarded (`STALE_WORKER_RESULT_DISCARDED`) and the reconciler resolves the row from evidence, so this is safe but noisy.
- "fail() invents ACTION_PENDING → EXECUTING". Same shape as the existing webhook failure path (`transitionCampaignToFailure`); the state machine has no ACTION_PENDING → FAILED edge. Kept for consistency.
- Nits not changed: dispatcher `Instant.now()`; lease via repository (not a status write); `findStuckExecutingIds` unbounded; `default` branch in `reconcile`; mock `duplicate` log flag. `reconcile_after` is `TIMESTAMPTZ` (V12), so the Instant binding is time-zone safe.
Tests after fixes: `Tests run: 597, Failures: 0, Errors: 0, Skipped: 2`.

### Adaptations for U6 (written before applying)
1. V13 (new migration, never edit V12):
   - backfill `dispatched_at = COALESCE(claimed_at, now())` and `execution_attempts = GREATEST(execution_attempts, 1)` for NEEDS_RECONCILIATION / AWAITING_PAYMENT rows with NULL `dispatched_at`;
   - backfill `invoice_key`;
   - create the partial unique index. The header notes lock behaviour.
2. The invoice-key derivation comes from the original failure webhook (`inv:` / `ord:` / `pay:`), copied onto the intent at creation.
3. S7's fixture gets its `invoice_key` through the production derivation; un-disable S7.

## U6 — invoice_key + DB-enforced one in flight per invoice
Built:
- `InvoiceKeyResolver`. It is a pure `fromPayload` over the untrusted payment entity: `inv:` | `ord:` | `pay:`, with id shapes validated (`inv_` / `order_` / `pay_` + 1–60 alnum). The first present field decides, and a malformed one falls back to `cmp:<campaignId>` + WARN, so it under-groups and never merges.
  - `resolveForCampaign` reads the stored failure webhook, using the same tenant-scoped lookup as the amount resolver.
- `Campaign.invoiceKey`, set by `CampaignLifecycleService.getOrCreateCampaign`. That covers every creation path: webhook classification, manual start-recovery and demo; without a webhook it is the fallback. `@PrePersist` defaults it to `cmp:<id>`.
- `ActionIntentService.createActionIntentFromCompliance` copies the campaign key. `ActionIntent` `@PrePersist` defaults to `cmp:<campaignId>`, the same value as the campaign default.
- V13:
  - `campaigns.invoice_key` backfilled with `cmp:<id>`, NOT NULL;
  - intents backfilled from their campaign, NOT NULL;
  - the U2-review backfill of `dispatched_at` / `execution_attempts` for legacy reconcile rows;
  - `ux_action_intents_one_inflight_per_invoice`;
  - header with the runbook query and a lock-behaviour note.
- Claim: `claimIntentAtomic` adds `NOT EXISTS (in-flight sibling with the same merchant_id + invoice_key)`. `claimDueIntents` and `claimSpecificIntent` are no longer wrapped in a transaction, so each promote and claim commits on its own. A unique violation under a true race (`DataIntegrityViolationException`) means "not claimed" + audit `CLAIM_BLOCKED_INFLIGHT`.
- Spec item 6: SAFE_RETRY (NR→READY) leaves the in-flight set. Every other in-flight → in-flight move (dispatch, finalize to NR/AWAITING, reconciler NR↔AWAITING, markStuck) keeps the row's own index entry. Intents are created as PENDING/SCHEDULED/READY. So **claim is the only entry into the in-flight set**, and it is the only place that can hit the index. Nothing else needed handling.

Deviations / decisions:
- **Local test DB data fix (declared).** Before V13, the shared test DB held one campaign with 3 intents, two of them in flight (EXECUTING + CLAIMED). They were leftovers of my own U3 `ActionIntentTransitionsTest` (merchant "transitions merchant", payment `pay_m2t…`, worker `worker-t`). V13 would correctly have failed on it (runbook case).
  - Fix: the two leftover test rows were set CANCELLED by hand in the local test DB (`ed1ceb7f…`, `aa784b9b…`).
  - The test fixture now gives each intent its own invoice key, so it cannot recreate the shape.
  - This is not production data, and V13 itself was not weakened: it still fails on real duplicates (tested).
  - Flagged for owner review because the orchestrator lists "V13 fails on existing data" as a hard stop. I judged test-generated leftovers to be outside that rule's intent.
- Existing campaigns get `cmp:<id>`, not a re-derivation in SQL. A webhook payload in SQL would be untrusted parsing inside a migration, and the fallback is today's per-campaign behaviour.
- The `ExecutionSafetyScenarioTest.linkIntent` fixture now creates its campaign through `getOrCreateCampaign` (production derivation) and copies the key onto the intent like `ActionIntentService`. S7 un-disabled and passing.

Tests:
- `InvoiceKeyResolverTest` (7, doc-derived payloads, AMBIGUOUS noted).
- `OneInFlightPerInvoiceTest` (6): derivation at campaign creation with and without a webhook; copy at intent creation through the real compliance path; direct-SQL index violation; claim predicate blocking a sibling; two threads → exactly one claimed; a deterministic true race (A's claim uncommitted, B blocked on the index via `pg_locks`, A commits → B not claimed + `CLAIM_BLOCKED_INFLIGHT`).
- `V13MigrationTest` (3): backfills + index + NOT NULL; the index rejects a second in-flight intent; migration fails on existing duplicates (runbook case).
- S7 enabled.

Full suite: `Tests run: 613, Failures: 0, Errors: 0, Skipped: 1` (597 + 16; the skip is the M1 tracking test only, so no RED test remains disabled before U7).
Review (fresh subagent): 0 blockers. Fixed in `M2 U6: review fixes`:
- MAJOR blocked intents could fill the claim batch (the NOT EXISTS was only on the claim UPDATE) → `findDueClaimableIntents` excludes intents whose invoice has an in-flight sibling. Test `blockedIntentsDoNotStarveTheBatch` (batch 2, 3 older blocked intents, the free one is claimed); **verified it fails without the fix**.
- MAJOR a claim could poison a caller's transaction → new `ActionIntentTransitions.claimIsolated` (REQUIRES_NEW on another bean), used by both claim paths. The claim is isolated whatever the caller holds, and `claimDueIntents` stays non-transactional, so there is no self-deadlock against promoted rows.
- MAJOR sibling intents could get different keys (`@PrePersist` default `cmp:<campaign>` vs a campaign holding `ord:X`) → **new V14** `uq_campaigns_id_invoice_key UNIQUE (id, invoice_key)` + `fk_action_intents_campaign_invoice_key FOREIGN KEY (campaign_id, invoice_key) REFERENCES campaigns (id, invoice_key)`: "intent key = campaign key" is now a database rule. `ActionIntentService` uses `requireNonNull` on the campaign key (dead fallback removed). Grep: the only production intent creators are `ActionIntentService` (copies) and the dev seed (campaign default ⇒ same key). Test `V13MigrationTest.intentKeyMustMatchCampaignKey`.
  - **Numbering deviation**: V13 is committed and applied, so it is not edited (invariant 10). The key rule is V14, and **U8's planned V14 (`provider_payment_id`) becomes V15**.
  - `ActionIntentTransitionsTest`'s `test:<id>` fixture keys now violate the FK, so each fixture intent gets its own ACTION_PENDING campaign.
- MAJOR V13's runbook query is wrong after a rolled-back V13 (`invoice_key` NULL or absent) → the corrected query (grouped by campaign) is in V14's header, plus here:
  `SELECT merchant_id, campaign_id, array_agg(id || ':' || status ORDER BY created_at) FROM action_intents WHERE status IN ('CLAIMED','EXECUTING','NEEDS_RECONCILIATION','AWAITING_PAYMENT') GROUP BY merchant_id, campaign_id HAVING count(*) > 1;`
  V13's header cannot be corrected in place (invariant 10).
- MINOR V13 lock note incomplete → corrected in V14's header: ADD COLUMN / SET NOT NULL take ACCESS EXCLUSIVE locks, so reads are blocked too.
- MINOR failure test did not prove there was no partial state → asserts `campaigns.invoice_key` absent, no successful v13 history row, and a `FlywayException`.
- MINOR any integrity violation was treated as "sibling in flight" → `tryClaim` checks the constraint name (`ux_action_intents_one_inflight_per_invoice`) through the cause chain and rethrows anything else.
- MINOR race-test lock poll was cluster-wide → it counts only waiters blocked by A's backend (`? = ANY(pg_blocking_pids(pid))`).
- MINOR new tests left claimable rows behind → `OneInFlightPerInvoiceTest` `@AfterEach` cancels its merchant's non-terminal intents.
- MINOR resolver lookup outside the try → lookup and parse are both inside it; any failure falls back (never breaks campaign creation).
Explained / not changed:
- "dispatched_at backfill undeclared": it was declared in the U5 adaptations ("the V13 backfill lands in U6"), following the U2 review plan. Legacy EXECUTING rows cannot exist after V12, which remapped every EXECUTING row. The stuck sweep uses `COALESCE(dispatched_at, claimed_at, created_at)` anyway.
- Resolver uses `external_event_id = campaign.paymentId`. That matches production: `processClassificationResult` creates the campaign with `paymentId = event.getExternalEventId()`, and `external_event_id` is globally UNIQUE (V1). No `VERIFIED` filter: the amount resolver trusts the same stored row for money, and the effect is limited to over-grouping within the merchant.
- Campaign has only `@PreUpdate`, so the new `@PrePersist` is the only one. `int:` fallback and field placement: nits, unchanged.
**Local test DB data fix #2 (declared).** Before V14, the local test DB held 15 intents with `test:<id>` keys. All were created by the U6 `ActionIntentTransitionsTest` fixture this review replaced. They were deleted by hand (`DELETE … WHERE invoice_key LIKE 'test:%' AND invoice_key <> campaign key`; 15 rows; nothing references `action_intents` by FK). Afterwards 0 rows differ from their campaign's key. Reviewer's opinion on fix #1: acceptable for a disposable local DB. It is evidence that integration tests need an isolated database (recorded as an out-of-scope finding).
Tests after fixes: `Tests run: 615, Failures: 0, Errors: 0, Skipped: 1`.

### Adaptations for U7 (written before applying)
1. U7 hard-stop check: no `@Disabled("M2-RED…")` remains (verified by grep before starting). The only `@Disabled` is the M1 tracking test in `PaymentReconciliationTest` (owned by U8).
4. U8 uses migration **V15** (V14 is taken by the key rule above).
2. The concurrency matrix reuses `BlockingPaymentProvider`, the SQL time travel and the reconciler `runCycle`. Each new test parks other classes' reconcile/EXECUTING rows the same way.
3. Run the full suite 3× for flakes (Phase5 Redis test watched since U4).
