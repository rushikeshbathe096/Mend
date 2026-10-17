# Pre-M3 bug sweep report (branch `bugfix/pre-m3`)

Branch `bugfix/pre-m3` from local `main` @ `e591173` (merge of `cleanup/pre-m3`). Nothing merged or pushed.
Registry: `docs/audit/bug-registry.md` (every open issue, classification, decision). Code grep for TODO/FIXME/HACK/XXX: no hits.

Baseline (`cd backend && env -u JWT_SECRET ./mvnw test`): `[INFO] Tests run: 683, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
Frontend baseline: `npm run lint` → `✖ 64 problems (53 errors, 11 warnings)`; `npm test` → 44 tests / 17 suites.

Every full run below is `env -u JWT_SECRET ./mvnw clean test` on the commit's tree, with 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`, and no `m2-exit-evidence-*` file.

| item | commit | subject | backend tests | seed (`randomizedInvoiceRaces`) |
|---|---|---|---|---|
| 0 | `4a49673` | docs: pre-M3 bug registry | 683 (docs only) | — |
| 1 | `e4247bd` | fix: single tenant guard | 784 | 2495452146825 |
| 2 | `20b6e3f` | fix: late capture before any action recovers the campaign | 798 | 2741390642488 |
| 3 | `44fc1bd` | fix: review reasons are not lost | 803 | 2936392274929 |
| 4 | `2e106e7` | fix: separate deferral counter | 805 | 3149251218003 |
| 5 | `4dd132c` | fix: synthetic recoveries are attributed SYNTHETIC | 807 | 3417732943475 |
| 6 | `efdd827` | test: no shared-table wipes | 809 | 3823335208896 |
| 6b | `1461ce6` | test: JWT secret config test independent of the shell environment | 809 | 3954641712555 |
| 7 | `8cd6ade` | frontend: fix lint | 809 (backend unchanged) | 4553783920785 |

The count never dropped (683 → 809). No test was deleted. Every changed assertion carries a `Bug sweep behaviour change` comment. Each revert proof below was made by temporarily changing only production code (or the guarded config), running the named tests, and then restoring the fix. Each restore was checked with `git diff --stat` or a grep for the temporary marker.

Process note (item 1): the first full run after the item-1 revert proof restored the files with `tar`, which keeps old mtimes. Maven's incremental compiler then kept the reverted classes and the run reported `Tests run: 784, Failures: 21`. That run was discarded, the files were touched, and the run recorded above is a `clean test`. From then on every full run is `clean test`, and revert proofs restore with `cp`.

---

## 1. SECURITY — single tenant guard (`e4247bd`)

**Root cause.** The membership rule was pasted into 12 services, and the copies disagreed about a missing user. Nine let `currentUser == null` through (`currentUser != null && !admin && !member`). `HumanApprovalService` rejected it. `MerchantConfigService` and `MerchantMemberService` answer it with 401 (`AuthenticationException`) before their own check. The audit said these two would throw an NPE; they don't. Any caller that passed `null` by accident got another merchant's data.

**Fix.** `com.mend.security.TenantGuard` is now the only rule, with two explicit entry points:
- `requireUserAccess(merchantId, user)`: the merchant is required, and the user is required. A system admin or a member of the merchant passes; anything else gets `TenantAccessDeniedException` (403).
- `requireSystemAccess(merchantId, SystemCaller)`: an internal call with no user. It must name itself (`new SystemCaller("…")`, non-blank), and the merchant is still required. No `null` means "system".

It is a final class with static methods, not a bean, so it cannot be mocked out of a service. All 31 guarded call sites in the 12 services call `requireUserAccess`, and the private copies are deleted. `MerchantMemberService.add/update/removeMerchantMember` used to check only the role; they now check tenant access first. The 401 for a missing user in `MerchantConfigService` and `MerchantMemberService` is kept.

**Callers that passed `null` (decided per caller):**

| caller | passes | decision |
|---|---|---|
| production (`src/main`) | none: every caller of the 32 guarded methods is a controller passing `@CurrentUser`, which `SecurityFilter` has already authenticated (401 otherwise) | user calls; no genuine system call exists today |
| `AnalyticsAmountAndAttributionIntegrationTest` (7 calls), `CustomerSummaryAttributionIntegrationTest` (2), `Phase19_5_FullMockE2EValidationTest` (1), `Phase20_RazorpayTestModeE2EValidationTest` (1) | `null` to `AnalyticsQueryService.*` | not system calls: they check the analytics figures a merchant sees. They now pass `TestUsers.memberOf(merchantId)`; assertions unchanged |
| `AgentDecisionQueryController.getMerchantAgentTimeline` | its own check (`merchantId` query param) | M3-owned AI path: not touched (registry BR-26) |
| `InternalSystemHealthController` | its own admin rule | not a tenant check (admin or merchant admin): unchanged |

**Tests.**
- `TenantGuardTest` (3): member, viewer and system admin pass. Another merchant's user, no user, no merchant and no memberships are denied. A system call needs a `SystemCaller` and a merchant.
- `TenantGuardServiceIntegrationTest` (98): for each of the 32 guarded service methods, a cross-tenant user is denied, a missing user is denied, and a member passes the guard.
  - A reflection test asserts that the list equals every public `com.mend.service` method taking an `AuthenticatedUser` (except `AuthService.getCurrentUser`).
  - `systemCallsStillWork`: `getOrCreateCampaign`, `transitionState`, `createReview` and `logEvent` run without a user, and a member then reads the result.

**Revert proof.** The 12 service files were checked out from HEAD (the old copies):
```
[ERROR] Tests run: 98, Failures: 21, Errors: 0, Skipped: 0
org.opentest4j.AssertionFailedError: Expected java.lang.RuntimeException to be thrown, but nothing was thrown.
```
The 21 failures were all `missingUserIsDenied`: getIntentHistory, getStrategyHistory, 4× AuditService, 5× AnalyticsQueryService, getComplianceHistory, 2× CampaignLifecycleService, 2× RecoveryActionQueryService, 3× CampaignQueryService, 2× WebhookQueryService.

**Counts.** 683 → 784.

## 2. Late capture before any action recovers the campaign (`20b6e3f`)

**Root cause.** The state machine had no CREATED/CLASSIFIED/ELIGIBLE/SCHEDULED → RECOVERED edge. So a late capture of the original payment on a campaign with no MEND action yet ended CANCELLED (M2 U8 deviation), although the money was back.

**Fix.**
- `CampaignStateMachine` keeps the four edges in a separate set. They are reachable only through `isValidRecovery` / `validateRecovery` with `ORIGINAL_PAYMENT_LATE_CAPTURE`. `isValidTransition` is unchanged, so `transitionState` and the MEND recovery paths (`ExecutionReconciler`, webhook intent path) cannot use them.
- `CampaignLifecycleService.recover(merchant, campaign, attribution, …)` is the only way to the gated edges. It writes RECOVERED and the attribution in one save and audits the transition.
- `DefaultPaymentReconciliationService` late capture uses `recover()` for every non-terminal state.

Money semantics are as described in the task. The campaign is RECOVERED with `ORIGINAL_PAYMENT_LATE_CAPTURE` and still never counts as a MEND recovery (analytics and the customer summary report it as late capture).

**Tests.**
- `CorrelationTest.lateCaptureBeforeAnyAction`: assertion changed from CANCELLED to RECOVERED (marked).
- New `lateCaptureBeforeAnyActionFromEveryPreActionState` (×4): RECOVERED, the attribution, the audit entry, and `recoveredByMend() == false`.
- `LateCaptureRecoveryEdgeTest` (8): the gated edges are only for late capture, existing edges are unchanged, terminal states never recover.
- `CampaignRecoverIntegrationTest` (2): `transitionState`, `recover(MEND)`, `recover(null)` and a cross-merchant `recover` are all rejected, and the campaign is unchanged.

**Revert proof.**
- (a) Old late-capture code back:
  ```
  [ERROR] Tests run: 16, Failures: 5, Errors: 0, Skipped: 0
  [ERROR]   CorrelationTest.lateCaptureBeforeAnyAction:245 expected: <RECOVERED> but was: <CANCELLED>
  [ERROR]   CorrelationTest.lateCaptureBeforeAnyActionFromEveryPreActionState:260 expected: <RECOVERED> but was: <CANCELLED>   (×4)
  ```
- (b) The naive fix, with the edges added to the generic table:
  ```
  [ERROR] Tests run: 10, Failures: 5, Errors: 0, Skipped: 0
  [ERROR]   CampaignRecoverIntegrationTest.otherCallersCannotUseTheEdge:60 Expected com.mend.exception.InvalidStateTransitionException to be thrown, but nothing was thrown.
  [ERROR]   LateCaptureRecoveryEdgeTest.preActionRecoveryNeedsLateCaptureAttribution:25 generic transition stays invalid ==> expected: <false> but was: <true>   (×4)
  ```

**Counts.** 784 → 798.

## 3. Review reasons are not lost (`44fc1bd`)

**Root cause.** `createReview` returned the existing PENDING review and dropped the new reason. For example, `CAPTURED_ON_TERMINAL_CAMPAIGN` after `DUPLICATE_PAYMENT_RISK` reached only the audit log, never the reviewer.

**Fix.**
- The new reason is appended as `\n[<instant>] [<CODE>] <reason>`. `CODE` is the reason's leading `CODE:`, or `UNCODED`. The append is audited as `REVIEW_REASON_APPENDED`.
- `ReviewQueueRepository.appendReasonIfPending` is a single `UPDATE … SET reason = reason || :entry`. It applies only while the review is PENDING and does not already contain that reason. So two concurrent reasons cannot lose each other, and the same reason twice stays a no-op (idempotent, as before).
- If the review is resolved between the read and the append, the reason opens a new review.
- **No migration:** `review_queue.reason` is `TEXT` (V1).

**Tests.** `ReviewReasonAppendIntegrationTest` (5):
- append with timestamp and code, first reason stays first
- no duplicate
- UNCODED
- a resolved review leads to a new review
- two threads appending at once (latch) keep both reasons

**Revert proof.** Old "return the existing review" behaviour:
```
[ERROR] Tests run: 5, Failures: 3, Errors: 0, Skipped: 0
[ERROR]   ReviewReasonAppendIntegrationTest.concurrentAppendsKeepBoth:129 DUPLICATE_PAYMENT_RISK: base ==> expected: <true> but was: <false>
[ERROR]   ReviewReasonAppendIntegrationTest.secondReasonIsAppended:61 DUPLICATE_PAYMENT_RISK: original payment pay_1 captured while plink_1 may be payable ==> expected: <true> but was: <false>
[ERROR]   ReviewReasonAppendIntegrationTest.uncodedReason:86 OPERATOR_ACTION_REQUIRED:NOT_SENT ==> expected: <true> but was: <false>
```

**Counts.** 798 → 803.

## 4. Separate deferral counter (`2e106e7`)

**Root cause.** Deferrals and SAFE_RETRY shared `execution_attempts` (cap 5 vs 3; ADR-002 deviation). The dispatch fence increments it on every dispatch, so 2 deferrals and then an Unknown outcome left it at 3, and the reconciler escalated instead of doing SAFE_RETRY.

**Fix.**
- **V17**: `action_intents.defer_attempts INT NOT NULL DEFAULT 0`, CHECK `>= 0`.
  - Additive only: existing rows keep `execution_attempts` unchanged, and no data is rewritten.
  - Intents deferred before V17 keep the old, stricter budget.
- `deferFromExecuting` moves the dispatch from `execution_attempts` to `defer_attempts` in the same fenced update.
  - `execution_attempts` now counts dispatches that were not deferred (the SAFE_RETRY budget). The reconciler's SAFE_RETRY logic is unchanged.
  - `execution_attempts + defer_attempts` is still the total number of dispatches.
- The deferral cap and backoff use `defer_attempts`: still 5 deferrals, then review. Audits and the review text report `defer_attempts`.

**Tests.**
- `ExecutionReconcilerTest.deferralsDoNotUseUpTheSafeRetryBudget`: 2 NOT_SENT deferrals, then an Unknown outcome, then time travel past the not-found grace, then one reconciler cycle. Result: READY via SAFE_RETRY, no escalation, no review.
- `deferAttemptsColumn`: V17 default and CHECK.
- The existing cap test `DeferralAndManualReviewTest.deferCodeNeverExhausts` (×4) is unchanged and green.

**Revert proof.** Old repository update and executor (entity and V17 kept so the test compiles):
- As written, the test fails at the first counter check:
  ```
  [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
  [ERROR]   ExecutionReconcilerTest.deferralsDoNotUseUpTheSafeRetryBudget:286 expected: <1> but was: <0>
  ```
- With the test's counter assertions temporarily removed, it shows the original symptom:
  ```
  [ERROR]   ExecutionReconcilerTest.deferralsDoNotUseUpTheSafeRetryBudget:298 SAFE_RETRY, not escalation ==> expected: <READY> but was: <FAILED>
  ```

**Counts.** 803 → 805.

## 5. Synthetic recoveries are attributed SYNTHETIC (`4dd132c`)

**Root cause.** A mock-mode `SyntheticCaptured` set the campaign RECOVERED with no attribution, so simulated money showed up as "attribution unknown".

**Fix.**
- **V18**: `ck_campaigns_recovery_attribution` also allows `'SYNTHETIC'`.
  - Only the constraint changes; no row is rewritten.
  - Synthetic recoveries from before V18 keep NULL, because inferring their attribution would fabricate it (invariant 3).
- `RecoveryAttribution.SYNTHETIC`. `ActionExecutionService` records a synthetic capture via `recover(…, SYNTHETIC, …)`.
- `recover()` refuses SYNTHETIC unless `mend.payment.provider=mock`. In razorpay mode no `SyntheticDebitSimulator` bean exists, so `SyntheticCaptured` cannot be produced at all.
- The analytics overview reports `recoveredSyntheticCount` / `amountRecoveredSynthetic` separately. `recoveredCampaigns` and `amountRecovered` stay MEND-only (the `switch` on attribution is exhaustive).

**Tests.**
- `SyntheticAttributionIntegrationTest`: a real claim and execute of a mock debit gives SYNTHETIC. Analytics shows 0 MEND recoveries, 1 synthetic, ₹499.00 synthetic, and 0 "unknown".
- `Phase20…syntheticAttributionIsImpossibleInRazorpayMode` (in the existing razorpay-mode context): no simulator bean, `recover(SYNTHETIC)` throws, and the campaign is unchanged.
- `Phase19_5…testScenarioA`: assertions changed from "attribution unknown" to SYNTHETIC (marked).

**Revert proof.**
- (a) Old `transitionState` for SyntheticCaptured:
  ```
  [ERROR] Tests run: 11, Failures: 2, Errors: 0, Skipped: 0
  [ERROR]   Phase19_5_FullMockE2EValidationTest.testScenarioA_HappyPathFullE2ERecovery:289 expected: <0> but was: <1>
  [ERROR]   SyntheticAttributionIntegrationTest.syntheticCaptureIsAttributedSynthetic:89 expected: <SYNTHETIC> but was: <null>
  ```
- (b) Mock-mode check disabled:
  ```
  [ERROR]   Phase20_RazorpayTestModeE2EValidationTest.syntheticAttributionIsImpossibleInRazorpayMode:520 Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.
  ```

**Counts.** 805 → 807.

## 6. TEST-RELIABILITY — no shared-table wipes (`efdd827`)

**Root cause.**
- 13 test classes (besides the 2 M3-owned ones) called `deleteAll()` on shared tables in the run's single database: `merchants`, `campaigns`, `action_intents`, `webhook_events`, `audit_logs` and others. That wiped every other class's rows and made results depend on class order.
- `debug=true` and Flyway TRACE logging wrote about 46 MB per run.
- The dev seed ran in every test context.

**Fix.**
- Each class deletes only its own rows. `testsupport.TestRows` deletes the class's own merchants (FKs cascade) and its own webhook events, by id or by external id. Audit rows are left in place.
- Assertions that relied on a wiped table (`findAll()` / `count()`) now check this test's rows, or a before/after delta (the 401 tests).
- Code that polls across merchants (scheduler, outbox publisher) gets other classes' claimable rows parked (`scheduled_at` / `next_attempt_at` + 30 days) instead of deleted.
- `ActionExecutionServiceTest` removes only its own campaign's compliance decisions.
- Classes changed:
  - `WebhookIntegrationTest`
  - `RedisStreamRetryServiceTest`, `RedisStreamPublisherConsumerTest`, `RedisWebhookEventConsumerTest`, `ConsumerConsolidationTest`
  - `ActionIntentServiceTest`, `ActionExecutionServiceTest`
  - `ActionSchedulerTransactionIntegrationTest`, `Phase10ActionSchedulerIntegrationTest`
  - `PaymentReconciliationTest`, `Phase5EndToEndIntegrationTest`, `Phase6EndToEndIntegrationTest`, `RazorpayTestModeEndToEndIntegrationTest`
- Not changed: `ClassificationServiceTest` and `AiRecoveryAgentIntegrationTest` (M3-owned AI path; registry BR-06b).
- `application-test.properties`:
  - `debug=false`
  - the Flyway TRACE and auto-config DEBUG loggers removed
  - `spring.jpa.show-sql=false` (unchanged)
  - `mend.dev.seed=false`: the seed no longer runs in test contexts. No test reads seeded rows; `DevDataInitializerTest` covers the property switch itself.

Full-run log: 45,785,256 → 1,958,701 bytes. Full clean run: 58 s.

**Tests.** `TestHygieneTest` (2):
- no `Repository.deleteAll()`, `deleteAllInBatch()` or `TRUNCATE` in test sources outside the two M3-owned classes
- the test profile has `debug`, `show-sql` and the dev seed off, and there is no `DevDataInitializer` bean

**Revert proof.** A `deleteAll()` put back in `ActionIntentServiceTest`, plus `debug=true` and `mend.dev.seed=true`:
```
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
[ERROR]   TestHygieneTest.noSharedTableWipes:58 use per-test cleanup (TestRows) instead:
com/mend/service/ActionIntentServiceTest.java:82  actionIntentRepository.deleteAll();   // REVERT PROOF
[ERROR]   TestHygieneTest.testProfileIsQuiet:64 debug=true writes the auto-config report and DEBUG logs ==> expected: <false> but was: <true>
```

**Counts.** 807 → 809.

## 6b. TEST-RELIABILITY — JwtSecretConfigurationTest independent of the shell (`1461ce6`)

**Root cause.** The test blanked `JWT_SECRET` with a property override, but an exported `JWT_SECRET` (OS environment) took precedence, so its two "fails to start" cases started.

**Fix.** The runner's environment has no OS-environment or system-property sources, the same isolation as `JwtSecretStartupTest`. Assertions are unchanged.

**Proof** (`JWT_SECRET=exported_shell_secret_… ./mvnw test -Dtest=JwtSecretConfigurationTest`):
- before: `[ERROR] Tests run: 5, Failures: 2, Errors: 0, Skipped: 0` (`defaultProfileWithoutJwtSecretFailsToStart`, `defaultProfileRejectsSecretShorterThan32Bytes`)
- after: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` with it set, and also with it unset (`JwtSecretStartupTest` 4/0 in both runs)

**Counts.** 809 → 809 (no new test; the existing 5 now hold in any shell).

## 7. QUALITY — frontend lint (`8cd6ade`)

**Root cause.** 35 `no-explicit-any`, 17 `react-hooks/set-state-in-effect` (pages fetched in an effect through a function that first called `setLoading(true)`), 1 `react-hooks/purity` (`Date.now()` in render), and unused variables.

**Fix.** No rule was disabled.
- `catch (err: unknown)` with `errorMessage(err, fallback)` (`src/lib/errors.ts`). `ApiError.data` is typed `ApiErrorBody`, request bodies are `unknown`, and DTO maps are `Record<string, unknown>`.
- Data loading goes through `useLoad` (`src/lib/useLoad.ts`):
  - The effect calls the loader through `useEffectEvent` when the request key (merchant, page, filters) changes or `reload()` is called.
  - `loading` is derived from which request has finished.
  - Loaders set state only after their `await` (checked for all 15 loaders) and drop superseded results (`isCurrent()`).
- `ConsoleLayout` derives the review count instead of resetting it in an effect.
- `StartRecoveryModal` renders a fresh body each time it opens (state starts over) instead of resetting state in an effect.
- `AuthContext` reads the localStorage session through `useSyncExternalStore`. The server snapshot is `null`, meaning loading, so hydration matches. `login`, `logout` and `selectMerchant` write storage and notify subscribers.
- Review expiry is compared with the time the reviews were loaded.
- One warning stays, with the reason in a comment: `@next/next/no-location-assign-relative-destination` in `lib/api.ts`. `fetchApi` runs outside React (no router), and the full page load on 401 deliberately drops in-memory session state.

**Tests.**
- `useLoad.test.tsx`: loading until the current request finishes, a superseded request never overwrites, `reload()`, a null key, and `errorMessage`.
- `AuthContext.test.tsx` (4): restore, fallback merchant, login/select/logout, corrupt storage.
- Results:
  - `npm run lint` → `✖ 1 problem (0 errors, 1 warning)`, exit 0
  - `npm test -- --ci` → `Test Suites: 19 passed`, `Tests: 53 passed` (was 17 / 44)
  - `npm run build` → `✓ Compiled successfully` (with `.next` removed first)

**Revert proof.**
- Old `analytics/page.tsx` back → `npm run lint` exit 1, `✖ 3 problems (2 errors, 1 warning)` (`56:19 no-explicit-any`, `64:5 set-state-in-effect`).
- `isCurrent()` forced to `true` →
  ```
  Tests: 1 failed, 4 passed, 5 total
  ● useLoad › a superseded request never overwrites the newer result
    Expected element to have text content: B
  ```

**Not verified.** I did not click through the pages in a browser; the evidence is the jest page tests and the production build.

---

## End state

- Backend 683 → **809** tests (+101 tenant guard, +14 late capture, +5 review reasons, +2 deferral counter, +2 synthetic, +2 test hygiene), 0 / 0 / 0.
- Frontend 44 → **53** jest tests; lint 53 errors → 0 errors (1 warning, with a reason); build succeeds.
- Migrations added: V17 (`defer_attempts`), V18 (attribution CHECK). V1–V16 untouched; neither migration rewrites data.
- Not touched: the AI path (M3-OWNED), the webhook-status test trade-off (BR-11), V1–V16.
- No STOP condition was hit:
  - money/recovery semantics changed only as described (items 2 and 5)
  - every tenant-guard caller had a clear decision (the table above)
  - no migration rewrites data
