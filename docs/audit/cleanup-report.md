# Cleanup report (branch `cleanup/pre-m3`)

Plan: the owner's cleanup prompt, Groups 1–9 (Groups 1–2 = items 1–2 of `docs/audit/unused-code-audit.md`).
Baseline: `[INFO] Tests run: 654, Failures: 0, Errors: 0, Skipped: 0` (audit baseline; `main` @ `b669205`).
Out of scope throughout: Phase-N test renames, test package moves, tenant/membership checks, the AI path, V1–V15. Nothing is merged or pushed.

## Commits

| group | commit | subject |
|---|---|---|
| 1 | `f4526cc` | cleanup: remove unused enums |
| — | `ec78300` | test: capture evidence on randomizedInvoiceRaces timeout |
| — | `3482f6e` | test: fix randomizedInvoiceRaces observer busy-poll |
| 2 | `f5892e0` | cleanup: remove unused repository methods |
| 3 | `572b717` | cleanup: shared payment-link id helper |
| 4 | (this commit) | fix: honest amounts in analytics |

## Investigation: `M2ExitCriteriaTest.randomizedInvoiceRaces` timeout

### The failure (one occurrence, 2026-10-11 19:08–19:11, single-test run on this branch after Group 1)

```
randomized race failed: seed=14339355386339 iteration=87 outcome=REJECTED (rerun with -Dm2.exit.seed=14339355386339)
Caused by: java.util.concurrent.TimeoutException
	at java.base/java.util.concurrent.FutureTask.get(FutureTask.java:204)
	at com.mend.integration.m2.M2ExitCriteriaTest.invoiceRace(...)        <- observer.get(10, SECONDS), not a worker join
Time elapsed: 197.5 s (87 iterations ≈ 2.2 s/iteration; normal is ≈ 0.2 s/iteration)
```

Timeline from that run's captured log (thread `pool-4-thread-4` is the observer):

| time | thread | event |
|---|---|---|
| 19:11:13.296 | main | last statement of the post-race `worker-1` poll (`update campaigns …`); then `done.set(true)`, `observer.get(10 s)` |
| 19:11:13.339 | observer | last line: `Executing prepared SQL statement [SELECT COALESCE(MAX(n), 0) …]` (printed before the connection is borrowed) |
| ≈19:11:23 | main | `observer.get` times out → `tearDown`: `shutdownNow()`, `awaitTermination(10 s)` |
| 19:11:33.351 | main | first `cancelOwnRows` statement: `awaitTermination` used its full 10 s, so the observer **did not respond to interrupt** |
| 19:11:33.351 → 34.438 | main | 1.1 s gap between two consecutive JdbcTemplate debug lines with no I/O in between |

No exception other than the timeout was logged; the Postgres container logged nothing in that window. That log (`TEST-…M2ExitCriteriaTest.xml`, 47 MB) was later overwritten by the reproduction runs below; the lines above were extracted from it first.

### 1. Pre-existing or Group 2? — not reproduced on either side

Each run is `./mvnw test` (full) or `./mvnw test -Dtest=M2ExitCriteriaTest#randomizedInvoiceRaces` (single), with the evidence capture of `ec78300` applied (test-only, no behaviour change). Runs were sequential unless noted.

| checkout | kind | seeds | result |
|---|---|---|---|
| `main` `b669205` (worktree) | single ×5 | 686747132263, 733126601195, 779160718711, 821816487075, 860771807212 | 5 pass (36–42 s) |
| `main` | full ×3 | 940653473528, 1043652383598, 1132470660915 | 3 × `Tests run: 654, Failures: 0, Errors: 0, Skipped: 0` |
| branch (Group 1 + uncommitted Group 2) | single, failing seed replayed | 14339355386339 | pass (40 s) |
| branch | single ×5 | 1194145966305, 1241853689140, 1285108811231, 1326188223143, 1363829162700 | 5 pass (34–43 s) |
| branch | full ×3 | 1433493266963, 1525465008272, 1609190924510 | 3 × `Tests run: 654, Failures: 0, Errors: 0, Skipped: 0` |
| branch | single, 24 busy-loop processes on 16 CPUs | 1720757899896 | pass (91 s) |
| main + branch + branch, 3 JVMs at once, ×2 | single | 1803075155357, 1802773233738, 1809083316235, 1866560931758, 1866400766676, 1866492700254 | 6 pass (42–48 s) |
| branch | single, Hikari `leak-detection-threshold=5000` + `pg_stat_activity` sampled every 1 s | 1942200828655 | pass |

25 runs, 0 failures. The seed does not reproduce the failure, so it is timing-dependent, not seed-dependent. Group 2 cannot be implicated (it removes uncalled repository methods), and there is no evidence the failure is specific to either side.

### 2. Did hygiene change the conditions?

| item | before hygiene (`730dd93`) | after (`5ab700d`, main, branch) |
|---|---|---|
| Hikari pool per context | default fixed pool: max 10, min idle 10 | max 10 (default), `minimum-idle=2`, `idle-timeout=10000` |
| connection timeout | 30 s (default) | 30 s (default) |
| database | shared dev DB `mend` | fresh `mend_test_<epoch>_<random>` per run, migrated from V1 |
| Postgres | `max_connections=100`, `lock_timeout=0`, `statement_timeout=0`, `deadlock_timeout=1s` | unchanged |

Spring contexts: `M2ExitCriteriaTest` shares `ExecutionSafetyScenarioTest`'s context (same `@Import` + `@MockitoBean`), so the race has one pool of 10.

Connection arithmetic for one race: 3 schedulers + the manual path, each ≤ 2 connections when `ActionIntentTransitions.claimIsolated` (`REQUIRES_NEW`) runs inside an outer transaction, plus the observer's 1 = **≤ 9 of 10**. The sampled run peaked at **6 connections** to the test database. Tight, but under the limit. The elastic pool (min idle 2) means extra connections are created on demand, but a Hikari borrow is interruptible and would have ended at `shutdownNow()`; the failing observer did not.

### 3. Evidence on the runs that could be measured

- Hikari leak detection (5 s): one warning, `DevDataInitializer.run` on `main` during context **startup** (dev seed, ≈5.4 s transaction), returned to the pool 1 s later ("unleaked"). It is before the races and unrelated. **No warning during the 200 races.**
- `pg_stat_activity` during the races: ≤ 6 connections, ≤ 1 active per sample, **0 lock waits**, no transaction older than ~0 s.
- Observer load: **≈ 65 000 queries per run (313–337 per iteration)**, **73–75 % of all log lines**, 47–50 MB of captured output per run (Spring Boot `debug=true` enables DEBUG on the `sql` logger group).
- `ec78300` now writes `backend/target/m2-exit-evidence-*.txt` on any race timeout (thread dump with lock owners, Hikari stats, `pg_stat_activity`, ungranted `pg_locks`, blocking pids). No run produced one.

### 4. Classification: A (test harness), probable; root cause not proven

- **Not B (product bug), as far as the evidence goes.** At the stall, every worker future had joined and `main`'s last poll had finished; the only thread in the database was the observer, running a read-only `SELECT` that cannot wait on row locks. Measured runs show no lock waits, no leaked connections, no long transactions.
- **Not C (resource limit) on the measured runs**: peak 6 of 10 connections; a pool wait would have answered the interrupt.
- **What the failing log does show**: a ≈ 20 s stall of the whole test JVM (neither thread logged; a further 1.1 s stall between two adjacent log calls), the run ≈ 10× slower than normal, and the stuck thread being the busy-polling observer, uninterruptible (socket read, monitor, or blocked stdout write; a thread dump would have said which). That points at host/JVM conditions (GC, stdout back-pressure, or load at the time) amplified by the observer's busy loop. It was not reproduced, so this is the most likely explanation, not a proven one.

### 5. Fix (owner-approved: "pause observer, continue")

`M2ExitCriteriaTest.invoiceRace`: the observer pauses 5 ms between polls (`LockSupport.parkNanos`). It already borrows one pooled connection per query and returns it, so it holds at most one, briefly. Timeouts (10 s / 20 s), the 200 iterations and every assertion are unchanged; no retry, no `@Disabled`. The observer samples less often (it was always documented as able to miss short states; the U6 unique index is the guarantee). The evidence capture stays in, so a recurrence produces a classified report instead of a bare timeout.

Post-fix verification (`./mvnw test`, Group 2 stashed, so the fix alone):

| run | seed | total |
|---|---|---|
| 1 | 2166130308841 | `[INFO] Tests run: 654, Failures: 0, Errors: 0, Skipped: 0` |
| 2 | 2259516901534 | `[INFO] Tests run: 654, Failures: 0, Errors: 0, Skipped: 0` |
| 3 | 2349090276975 | `[INFO] Tests run: 654, Failures: 0, Errors: 0, Skipped: 0` |

No evidence file was written. Observer queries for the whole class (both race tests, 201 races) in run 3: 1 865, down from ≈ 65 000 for the randomized test alone before the fix.

## Group 2: remove unused repository methods

Removed (audit item 2): `ActionIntentRepository.findDueIntents`, `AuditLogRepository.findByActorId`,
`CampaignAttemptRepository.findFirstByCampaignIdOrderByAttemptNumberDesc`,
`CampaignRepository.{findByPaymentId, findByMerchantIdAndSubscriptionId, findScheduledCampaignsByMerchant}`,
`ClassificationResultRepository.existsByEventId`, `ComplianceDecisionRepository.existsByCampaignIdAndStatus`,
`WebhookEventRepository.{findByProcessingStatus ×2, countByMerchantIdAndEventType}`, plus the two imports this left unused
(`java.time.Instant` in `CampaignRepository`, `ComplianceStatus` in `ComplianceDecisionRepository`).

- Proof of no use: each name has 0 matches in `backend/src`, `frontend/src` and `ai-service` after the change; Spring Data only parses declared methods, so nothing can reach them at runtime.
- Tests: `./mvnw test` → `[INFO] Tests run: 654, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (seed 2475853721078; after the import cleanup, seed 2584640586156, same totals).

## Group 3: shared payment-link id helper

- `com.mend.provider.PaymentLinkSnapshot.isLinkId(String)`: `!= null && startsWith("plink_")`, the exact rule both copies used (no behaviour change; the stricter `RazorpayPaymentProvider.LINK_ID` pattern is adapter input validation and stays where it is).
- `ExecutionReconciler.reconcileAwaitingPayment` and `DefaultPaymentReconciliationService` (2 call sites; its private `isPaymentLinkReference` is removed) use it. `grep -rn 'startsWith("plink_")' backend/src/main` → none left.
- New test `PaymentLinkSnapshotTest.isLinkIdAcceptsOnlyPaymentLinkIds` (null, empty, `pay_`, upper case, attempt key → false).
- Tests: `./mvnw test` → `[INFO] Tests run: 655, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (seed 2730759722708; +1 = the new test).

## Group 4: honest amounts in analytics (invariants 1 and 3)

**Amounts.**
- `WebhookAmountResolver.extractMoneyMinor` (the strict M1 rule: positive integral minor units + supported currency) is now the only amount parser.
- `resolveAmount` (the `BigDecimal` display path for `PaymentController`, `CustomerController`, `HumanApprovalService`) is `resolveOriginalAmount(...).map(toDisplay)`: an exact paise→rupees conversion (`BigDecimal.valueOf(minor, 2)`), empty under the same conditions as the strict parser.
- The lenient `extractAmountFromWebhook` (any `asLong`, no currency check) is deleted. Its only caller was `resolveAmount`.
- `AnalyticsQueryService` no longer parses payloads itself; its copy, with the fabricated `1000.00` default, is deleted. It sums `long` minor units exactly (`Math.addExact`) and converts to display only at the DTO.
- A campaign with no resolvable amount (missing, non-integral, unsupported currency, no webhook) is left out of every sum and counted in the new `amountUnknownCount` (overview, per failure class, per strategy).

**Attribution.** Every "recovered" metric now means recovered by MEND (`recovery_attribution = MEND`):
- covered: `recoveredCampaigns`, `successfulRecoveries`, `recoveryRate`, `amountRecovered`; per-class and per-strategy recovered counts, revenue and rates; `recoveryRateByStrategy` / `ByFailureClass`; and the funnel's `RECOVERED` stage
- reported separately: `recoveredLateCaptureCount` + `amountRecoveredLateCapture` (`ORIGINAL_PAYMENT_LATE_CAPTURE`), and `recoveredAttributionUnknownCount` + `amountRecoveredAttributionUnknown` (NULL on RECOVERED rows from before V15)
- `amountRemainingAtRisk` is the exact sum over campaigns not in RECOVERED
- `statusBreakdown` still counts states, so it shows every RECOVERED row

**API shape.** Money fields in `AnalyticsOverviewDto`, `AnalyticsFailureBreakdownDto` and `AnalyticsStrategyPerformanceDto` changed type from `double` to `BigDecimal`, with the same JSON names and the same unit (rupees). This matches the existing display fields from `resolveAmount`. The frontend reads them unchanged. The new fields are additive.

**`double` / `float` left in main (grep gate), all non-money:**
- recovery / success / conversion percentages (`recoveryRate`, `recoveryRateBy*`, `*RatePercent`, `AnalyticsQueryService.percent` / `roundTwoDecimals`)
- `OperationalHealthDto.aiFallbackRate`
- AI confidence averaging (`AnalyticsQueryService` 265–285)
- `averageAttemptsPerCampaign`
- the `PageResponse` page count
- the string literal "possible double payment" in `DefaultPaymentReconciliationService`

**Jackson 2.** `com.fasterxml.jackson.core:jackson-databind` moved to `<scope>test</scope>`. Main compiles without it (`./mvnw compile`, BUILD SUCCESS). It can't be removed entirely: 4 test classes still use its `ObjectMapper` (`AiAgentIntegrationTest`, `Phase19_5_FullMockE2EValidationTest`, `Phase20_RazorpayTestModeE2EValidationTest`, `RazorpayTestModeEndToEndIntegrationTest`). `jackson-annotations` (used by the Razorpay DTOs and by Jackson 3) stays at compile scope.

**Consequence to know.** The SYNTHETIC mock capture path (`ActionExecutionService`, mock provider only) moves a campaign to RECOVERED without setting an attribution. Mock-mode/demo dashboards therefore show those recoveries as "attribution unknown", not as recovered by MEND. That is correct under invariant 3, since a synthetic capture is not provider evidence. The execution path is not changed here.

**Tests**
- Changed assertions, each marked `// cleanup behaviour change: …`:
  - `WebhookAmountResolverTest.existingResolveAmountResultsAreUnchanged`: `"100"` without currency and `100.5` were shown as `1.00`; both are now empty.
  - `AnalyticsObservabilityIntegrationTest.testAnalyticsOverviewMetrics`: the legacy RECOVERED row is attribution-unknown (recovered 1→0, rate 50.0→0.0); revenue at risk was `> 0` only from the 1000.00 default and is now `0.00` with `amountUnknownCount = 2`.
  - `Phase19_5_FullMockE2EValidationTest` Scenario A: the synthetic recovery is attribution-unknown (recovered 1→0, rate 100.0→0.0); its ₹500.00 is asserted exactly in `amountRecoveredAttributionUnknown` and `revenueAtRisk`.
- New regression tests:
  - `AnalyticsAmountAndAttributionIntegrationTest.missingAmountNeverProducesANumber`
  - `…lateCaptureIsNotCountedAsMend` (also covers NULL attribution, per-class, per-strategy, recovery rates and the funnel)
  - `WebhookAmountResolverTest.resolveAmountIsEmptyWheneverTheStrictAmountIsEmpty`
- `./mvnw test` → `[INFO] Tests run: 658, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (seed 3250871087065; +3 = the new tests).
