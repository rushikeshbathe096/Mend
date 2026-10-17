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
| 4 | `b520e8c` | fix: honest amounts in analytics |
| — | `8835869` | test: stop the scheduled Redis consumer poll racing consumer tests |
| 5 | `3eb13e0` | cleanup: remove legacy webhook publish chain |
| 6 | `72a1710` | cleanup: drop legacy status values + CHECK constraints |
| 7 | `58c5569` | config: complete .env.example and safe default profile |
| 8 | `3ab707c` | frontend: M2 states and demo page |
| 9 | `294eea4` | docs: README index and limitations |

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

## Harness: background Redis consumer poll (`8835869`)

`RedisWebhookEventConsumer.pollAndProcessMessages` was `@Scheduled` (every 2 s) in every test context. While Group 5 was in progress, the full suite failed once in `RedisStreamPublisherConsumerTest.consumer_HandlerFailure_DoesNotAcknowledgeMessageAndSupportsRecovery` (`expected: <1> but was: <0>`, seed 3645313379849). The log shows thread `scheduling-1` consuming the test's message between its publish and `processor.setSimulateFailure(true)`. The race existed before; the outbox publish path (`markPublished` after the Redis add) widened the window.

Fix: the background poll now goes through `scheduledPoll()`, which honours `mend.consumer.scheduled-enabled` (default `true`; production unchanged), the same pattern as the scheduler, outbox publisher and reconciler. `application-test.properties` sets it `false`. No test relies on background consumption: every consumer test calls `pollAndProcessMessages` / `processPendingMessages` itself.

Committed alone, with Group 5 stashed: `./mvnw test` → `[INFO] Tests run: 658, Failures: 0, Errors: 0, Skipped: 0` (seed 3820937730003; 0 consumer log lines from `scheduling-1`).

## Group 5: remove the legacy webhook publish chain

**Precondition.** Before deleting anything, `webhook_events` was grouped by `publish_status` in the dev DB `mend` and in every `mend_test_*` DB present:

| database | publish_status counts |
|---|---|
| `mend` | PUBLISHED=991 |
| `mend_test_1791726135_1b058308` | PENDING=205, PUBLISHED=2 |
| `mend_test_1791737945_929dc892` | PENDING=202, PUBLISHED=990 |

The enum value is `PUBLISH_FAILED`; there is no `FAILED`. Neither value appears, so no pre-outbox failed publication is left for the retry service.

**Deleted (main):**
- `consumer/RedisStreamConsumer`
- `handler/WebhookEventHandler`, `handler/DefaultWebhookEventHandler`
- `service/WebhookPublisherRetryService`
- `publisher/NoOpWebhookEventPublisher`, `publisher/WebhookEventPublisher`
- `RedisWebhookEventPublisher.publish(WebhookEvent)`, with its `WebhookEventRepository` dependency and the duplicate message/envelope builder
- `WebhookEventRepository.findByPublishStatus` and `OutboxEventRepository.existsByAggregateTypeAndAggregateId`: their only callers were the deleted retry service and test

`RedisWebhookEventPublisher` is now only the `OutboxEventTransport`.

**Moved:** the message/envelope construction from `WebhookService.serializeOutboxPayload` into `WebhookOutboxPayload.forWebhook(WebhookEvent)`. Ingestion and tests now build the identical payload; the deleted `publish()` had a third copy.

**Tests** (net count unchanged, 658 → 658):

| test | before | after |
|---|---|---|
| `ConsumerConsolidationTest.testSingleActiveConsumerInContext` | the legacy class is not a bean | exactly one bean of any `com.mend.consumer` type exists, and it is `RedisWebhookEventConsumer` (+ the existing one-bean check) |
| `RedisStreamPublisherConsumerTest` (5) | legacy `publish()` + legacy handler | `publishThroughOutbox` = outbox row → `publishOutbox` → `markPublished` (the per-row production path), real consumer; the schema-version test now sends a version-99 envelope through the real consumer instead of the deleted handler |
| `RedisWebhookEventConsumerTest` (2 of 4) | legacy `publish()` | same outbox path |
| `RedisWebhookEventPublisherTest` (3 of 4) | `publish()` fields / failure / null | `publishOutbox` fields / failure / null, payload from `forWebhook` |
| `RedisFailureAndRetryTest` (2), moved `publisher` → `service` | retry service (never scheduled in production) | the same two scenarios on the outbox against real PostgreSQL + Redis, via `OutboxPublisherService.publishOne` on the test's own row: Redis down → webhook `PUBLISH_FAILED`, outbox `FAILED` with backoff; Redis back → same row `PUBLISHED`, attempts 2, consumed and `PROCESSED` |

**Outbox publish failure/retry coverage after this group:**
- `OutboxPublisherServiceTest`: `redisFailureLeavesEventRetryableAndRecordsBackoff`, `redisRecoveryPublishesPreviouslyFailedOutboxEvent`, `crashAfterRedisAcceptsCausesSafeAtLeastOnceRepublish`, `successfulPublicationMarksPublishedOnlyAfterRedisAccepts`
- `RedisFailureAndRetryTest`: both tests, as integration
- `TransactionalOutboxIntegrationTest.crashWindowRedeliveryPreservesMetadataAndRemainsConsumerIdempotent`

**Run:** `./mvnw test` → `[INFO] Tests run: 658, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (seed 3933075509317).

## Group 6: drop legacy status values + CHECK constraints (V16)

**Removed enum values** (none was ever written by code; 0 rows in the dev DB and in every `mend_test_*` DB present):
- `CampaignStatus`: `STRATEGY_ASSIGNED`, `WAITING`, `ACTION_EXECUTING`, `RETRY_SCHEDULED`, `PENDING_REVIEW`, `ESCALATED`, `MANDATE_INVALID`, `STOPPED`
- `ActionIntentStatus`: `PROCESSING`. Its only read, in `ComplianceService.checkDuplicateActionExists`, is removed.
- `ReviewQueueStatus`: `IN_REVIEW`, `OVERRIDDEN`. `ESCALATED` stays: `HumanApprovalService` writes it.
- `WebhookEventStatus`: `PROCESSING`, `IGNORED`, `INVALID_SIGNATURE`

The strings `"ACTION_EXECUTING"` (an audit event type) and `"STOPPED"` (an agent-record status) are unrelated and unchanged.

Data checked before writing V16 (`GROUP BY` on each column):

| database | campaigns.current_state | action_intents.status | review_queue.status | webhook_events.processing_status |
|---|---|---|---|---|
| `mend` | ACTION_PENDING, CANCELLED, ELIGIBLE, EXECUTING, EXHAUSTED, RECOVERED | AWAITING_PAYMENT, CANCELLED, CLAIMED, EXECUTING, EXPIRED, FAILED, NEEDS_RECONCILIATION, READY, SUCCEEDED | APPROVED, PENDING | PROCESSED, VERIFIED |
| `mend_test_1791726135_1b058308` | + CREATED | + SCHEDULED | + CLOSED | PROCESSED, VERIFIED |
| `mend_test_1791737945_929dc892` | ACTION_PENDING, CANCELLED, ELIGIBLE, EXHAUSTED, RECOVERED | CANCELLED, CLAIMED, FAILED, READY, SUCCEEDED | APPROVED, PENDING | PROCESSED, VERIFIED |

**`V16__status_check_constraints.sql`:**
- adds `ck_campaigns_current_state`, `ck_review_queue_status` and `ck_webhook_events_processing_status`
- replaces V12's `ck_action_intents_status` with the same set minus `PROCESSING`
- the header carries the runbook query
- a leading `DO` block raises `V16: legacy status values present, no data was changed: …` before any DDL when a stored value is outside the sets; Flyway's per-migration transaction rolls it back, and nothing is ever rewritten
- V1–V15 untouched

**Tests**
- New `V16MigrationTest` (throwaway schema migrated to V15, the real V16 file applied):
  - `legacyRowsMakeV16FailWithoutRewritingData`: a legacy value in each of the 4 columns → V16 fails with that message; all 4 values unchanged, no new constraint, V12's constraint still present, schema still at version 15
  - `constraintsMatchTheEnums`: after V16 every value of the 4 Java enums is accepted and every removed value is rejected (`DataIntegrityViolationException`)
- `ActionIntentStateMachineTest`, marked `// cleanup behaviour change`: the `PROCESSING.isTerminal()` assertion became `valueOf("PROCESSING")` throws; the fixed not-in-flight list became an exhaustive check over `ActionIntentStatus.values()`.
- `./mvnw test` → `[INFO] Tests run: 660, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (seed 4260205406699; +2 = `V16MigrationTest`).

**Frontend.** Removed the `Badge` cases `PENDING_REVIEW`, `IN_REVIEW`, `OVERRIDDEN`, `IGNORED` and the webhook filter options `PROCESSING`, `IGNORED`.
- `npm test -- --ci` → `Tests: 33 passed, 33 total`
- `npm run build` → success
- `eslint` on the two files reports the same 3 problems before and after (pre-existing: `no-explicit-any` and `set-state-in-effect` in `webhooks/page.tsx`, an unused `type` prop in `Badge.tsx`)

## Group 7: complete .env.example and safe default profile

**Default profile.** `spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}` is removed from `application.properties`. Without `SPRING_PROFILES_ACTIVE` the app runs Spring's `default` profile, so the dev seed and `/api/v1/demo` are off.
- `docker-compose.yml` runs only PostgreSQL and Redis, with no backend service, so there is nothing to set there.
- The README setup section now starts the backend with `SPRING_PROFILES_ACTIVE=dev` explicitly and says the backend has no default profile.

**Proof** (jar from this tree, each run on its own throwaway database, both dropped afterwards; token from `/auth/bootstrap` + `/auth/login`):

| start | profile line in log | `DevDataInitializer` log lines | rows after the HTTP checks | `GET /api/v1/demo/scenarios` no token / with token |
|---|---|---|---|---|
| no `SPRING_PROFILES_ACTIVE` | `No active profile set, falling back to 1 default profile: "default"` | 0 | merchants=1 (the bootstrap call), campaigns=0, webhook_events=0, `MEND_DEMO_DATASET_V1`=0 | 401 / **404** |
| `SPRING_PROFILES_ACTIVE=dev` | `The following 1 profile is active: "dev"` | 2 | merchants=14, campaigns=529, webhook_events=990, `MEND_DEMO_DATASET_V1`=1 | 401 / **200** |

Both runs applied 16 migrations, so V16 also succeeds on a fresh database.

**`.env.example`:**
- Removed `SPRING_JPA_HIBERNATE_DDL_AUTO`, replaced by a comment: Flyway owns the schema, and `ddl-auto=validate` is fixed in `application.properties`.
- Added every variable the audit listed as missing, with the code's defaults:
  - `SPRING_PROFILES_ACTIVE` (empty = `default`)
  - `MEND_CORS_ALLOWED_ORIGINS`
  - `MEND_REDIS_RETRY_MAX_ATTEMPTS`
  - `MEND_OUTBOX_{PUBLISHER_ENABLED, POLL_INTERVAL_MS, BATCH_SIZE, RETRY_BASE_DELAY_SECONDS, RETRY_MAX_DELAY_SECONDS}`
  - `MEND_AI_{CONNECT,READ}_TIMEOUT_MS`
  - `POSTGRES_DB_URI` (ai-service)
  - `RAZORPAY_SINGLE_TENANT_MERCHANT_ID`
  - `RAZORPAY_API_BASE_URL`
  - `RAZORPAY_{CONNECT,READ}_TIMEOUT_MS`
  - `NEXT_PUBLIC_API_URL`

**`application.properties`.** Every property that code read only through an inline `@Value` default is now listed with that same default (computed from all `${…}` keys in main, not copied from the audit):
- `mend.scheduler.{enabled, scheduled-enabled, poll-interval-ms, batch-size, lease-duration-minutes, default-retry-delay-minutes, worker-id}`; an empty worker id still means a generated `worker-<random>`
- `mend.consumer.{scheduled-enabled, poll-interval-ms}`
- `mend.compliance.min-confidence`, `mend.recovery.strategy.min-confidence` (both M3-owned; documented only, values unchanged)

**Tests.** `./mvnw test` → `[INFO] Tests run: 660, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (seed 4494755797558).

## Group 8: frontend M2 states and demo page

- **`Badge`**: `AWAITING_PAYMENT` uses the in-progress style (indigo), `NEEDS_RECONCILIATION` the attention style (amber), and `EXECUTING` already had indigo. Before this, the first two fell through to the grey default.
- **Actions page**: the intent status filter adds `EXECUTING`, `NEEDS_RECONCILIATION` and `AWAITING_PAYMENT`.
- **Campaign page**: the intent timeline step comes from the new `src/lib/intentStatus.ts` (`intentStepStatus`):
  - only `SUCCEEDED` is success; the dead `ai.status === 'COMPLETED'` comparison is gone (audit item 3)
  - `FAILED` / `EXPIRED` are failure
  - `SCHEDULED`, `READY`, `CLAIMED`, `EXECUTING`, `NEEDS_RECONCILIATION` and `AWAITING_PAYMENT` are in progress (the three M2 states used to show as the generic `PENDING`)
  - everything else is pending, as before
- **Demo page**: a 404 from `/demo/scenarios` (the backend without the dev profile, see Group 7) shows the empty state "Demo disabled", which names `SPRING_PROFILES_ACTIVE=dev`. Any other error is still shown as an error.

**Tests**
- New `intentStatus.test.ts` (3): only `SUCCEEDED` is success and `COMPLETED` is not; the M2 states are in progress; the other statuses keep their mapping.
- `Badge.test.tsx` +1: render test for the M2 states' styles.
- `demo/page.test.tsx` +2: 404 → "Demo disabled" with no error and no scenario buttons; 500 → error.
- `npm test -- --ci` → `Tests: 39 passed, 39 total` (was 33).
- `npm run build` → success.
- **`npm run lint` does not pass, before or after this group:** `✖ 64 problems (53 errors, 11 warnings)` at `58c5569` and the same after. On the 8 files this group touches, it is 13 problems before and 13 after; the new files add none. These are pre-existing `no-explicit-any`, `react-hooks/set-state-in-effect` and unused-variable findings across the frontend. Fixing them means restructuring effects in pages this group does not otherwise change, so it is listed as a finding instead.

**Backend**: unchanged in this group (last full run 660/0/0/0 at `58c5569`).

## Group 9: docs (README index and limitations)

- **`README.md`**:
  - Replaced the 10 broken links (`ARCHITECTURE`, `SECURITY`, `RELIABILITY`, `DEMO`, `SETUP`, `PROJECT_STRUCTURE`, `PHASE21_PACKAGE_INDEX`, `PHASE20_FORENSIC_FINDINGS`, `CV_BULLETS`, `INTERVIEW_GUIDE`) with links to documents that exist: M1/M2 summaries and specs, ADR-001/002, the hygiene and audit reports, `AGENTS.md`, `.env.example`.
  - Dropped the closed "provider lease-expiry duplicate-call boundary" from Limitations; the index says where M2 closed it.
  - New "Profiles" table (default vs `dev`) and "Analytics" note (strict amounts, `amountUnknownCount`, MEND-only "recovered", late capture and unknown attribution reported separately).
  - The stale "276 Java / 32 Python / 23 frontend tests" line now states what this cleanup measured (backend 660, frontend 39) and says the ai-service tests were not re-run (M3-owned).
- **`frontend/README.md`**: the create-next-app boilerplate is replaced by a pointer to the root README and the three commands.
- **Link check**: every relative `](…)` target in `README.md` and `frontend/README.md` exists (20 of 20; there were 10 missing before).
- **Tests**: `./mvnw test` → `[INFO] Tests run: 660, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (seed 4735883721066).

## End state

- Backend: 654 → **660** tests; 0 failures, 0 errors, 0 skipped.
  - +1 Group 3 (`isLinkId`); +3 Group 4 (analytics + resolver regressions); +2 Group 6 (V16)
  - Group 5 net 0; no test deleted
  - Changed assertions carry `// cleanup behaviour change` with the reason
- Frontend: 33 → **39** jest tests; `next build` succeeds; `npm run lint` has the same 64 pre-existing problems as before the cleanup.
- `randomizedInvoiceRaces` passed in every full run after the harness fix:

  | run | seed |
  |---|---|
  | fix verification ×3 | 2166130308841, 2259516901534, 2349090276975 |
  | Group 2 | 2475853721078, 2584640586156 |
  | Group 3 | 2730759722708 |
  | Group 4 | 3250871087065 |
  | consumer flag | 3820937730003 |
  | Group 5 | 3933075509317 |
  | Group 6 | 4260205406699 |
  | Group 7 | 4494755797558 |
  | Group 9 | 4735883721066 |

  One full run during Group 5 failed in a different test (seed 3645313379849), explained in the consumer-poll section above. No `m2-exit-evidence-*` file was written by any run.
- Nothing merged or pushed. V1–V15 untouched. No Phase-N renames, no test package moves (apart from `RedisFailureAndRetryTest`, which followed its subject into `com.mend.service`), no tenant/membership changes, no AI-path changes.

## Out-of-scope findings (not changed)

1. **`ComplianceService.checkDuplicateActionExists`** counts PENDING / SCHEDULED / READY / CLAIMED / EXECUTING / SUCCEEDED as an existing action, but not `NEEDS_RECONCILIATION` or `AWAITING_PAYMENT`. A second intent for the same attempt number could pass compliance while one is in reconciliation or awaiting payment. The one-in-flight index (V13) still prevents two from being in flight per invoice. Compliance behaviour, so it was not changed here.
2. **`jwt.secret`** defaults to `dev_default_jwt_secret_key_must_be_at_least_32_bytes_long` in `application.properties` for every profile, now including `default`. A deployment without `JWT_SECRET` signs tokens with a public key. It should fail to start instead, or the default should move to `application-dev.properties`.
3. **`CustomerController`** customer summary counts every RECOVERED campaign as `recoveredCampaigns`, regardless of attribution (lines 110, 117), unlike analytics after Group 4.
4. **The analytics UI** does not show the new fields yet (`amountUnknownCount`, late capture, attribution unknown). Mock-mode/demo dashboards will show 0 MEND recoveries, because SYNTHETIC captures set no attribution.
5. **The webhook filter** still offers `UNVERIFIED` and `DUPLICATE`, which are not `WebhookEventStatus` values.
6. **Frontend lint**: 64 pre-existing problems (53 errors), mostly `no-explicit-any` and `react-hooks/set-state-in-effect`.
7. **Test hygiene**:
   - 15 test classes call `deleteAll()` on shared tables (webhooks, classifications) in the run's shared database.
   - `debug=true` in `application-test.properties` enables DEBUG SQL logging (tens of MB per run).
   - `DevDataInitializer` holds one connection for ≈5 s during test context startup (Hikari leak-detection warning).
8. **Jackson 2** remains at test scope only because of 4 integration tests' `ObjectMapper`. `RedisWebhookEventPublisher` keeps a now-unneeded `@Primary`.
9. **Local state**:
   - Two `mend_test_*` databases from aborted runs (`…1791726135…`, `…1791737945…`) remain until the 24 h sweep.
   - A git worktree from another session is registered at `/tmp/claude-1000/…/b1176de1…/scratchpad/main-wt` (`b669205`, detached). It was not created by this work and was left alone.

## Follow-up: out-of-scope findings 1, 2, 3 and 5 fixed

Owner request after the cleanup: fix four of the findings above, one commit each, with the full suite after each one. Nothing is merged or pushed.

| finding | commit | subject | backend `./mvnw test` | seed (`randomizedInvoiceRaces`) |
|---|---|---|---|---|
| 2 | `0d7e36c` | security: no default JWT secret outside dev | `[INFO] Tests run: 665, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` | 429886477657 |
| 1 | `e3b4773` | fix: compliance duplicate check includes M2 in-flight states | `[INFO] Tests run: 669, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` | 664747029071 |
| 3 | `c6d5834` | fix: customer summary counts only MEND recoveries | `[INFO] Tests run: 671, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` | 831541495399 |
| 5 | `f803138` | frontend: webhook filter only real statuses | `[INFO] Tests run: 671, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (backend unchanged) | 945862665975 |

### Finding 2: no default JWT secret outside dev (`0d7e36c`)

- `application.properties`: `jwt.secret=${JWT_SECRET:}`, so there is no default secret.
- `application-dev.properties`: `jwt.secret=${JWT_SECRET:dev_default_jwt_secret_key_must_be_at_least_32_bytes_long}`, a dev-only value. `application-test.properties` keeps its own secret, unchanged.
- `JwtService` fails startup with one of two clear messages:
  - missing or blank: `jwt.secret is not configured: set JWT_SECRET (at least 32 bytes). Only the dev profile has a built-in development secret.`
  - fewer than 32 UTF-8 bytes: `jwt.secret is too short: JWT_SECRET must be at least 32 bytes (HS256)`
- `.env.example`: `JWT_SECRET` is marked required outside the dev profile, and the backend fails to start without it.
- `README.md`: the Profiles table has a new `JWT_SECRET` column (required in `default`, optional in `dev`).
- New `JwtSecretConfigurationTest` (5 tests). Each context loads the real `application*.properties` through `ConfigDataApplicationContextInitializer`:
  - default profile with `JWT_SECRET=` → context fails, root cause is the "not configured" message
  - default profile with a 31-byte secret → fails as "too short"
  - default profile with a 32-byte secret → starts
  - `dev` profile without `JWT_SECRET` → starts
  - the constructor rejects null, blank and short secrets
- Deviation: these are `ApplicationContextRunner` contexts (`JwtService` + `ObjectMapper` plus the shipped config files), not a full application boot. A full boot would need the database, and in the `dev` profile it would seed the run's shared test database.

### Finding 1: the compliance duplicate check includes the M2 in-flight states (`e3b4773`)

- `ComplianceService.countsAsExistingAction(status)` = `status.isInFlight()` (CLAIMED, EXECUTING, NEEDS_RECONCILIATION, AWAITING_PAYMENT) or one of PENDING, SCHEDULED, READY, SUCCEEDED. Those four are the non-in-flight states the check already counted. Any state added to `isInFlight()` later is counted automatically.
- New tests in `ComplianceServiceTest` (+4):
  - `secondIntentForSameAttemptIsBlockedWhileFirstIsInM2InFlightState`, parameterized for `AWAITING_PAYMENT` and `NEEDS_RECONCILIATION`: an ELIGIBLE campaign on attempt 1 already has an attempt-1 intent in that state, and compliance returns `DUPLICATE_ACTION`
  - `endedFirstIntentDoesNotBlock` (control): a FAILED first intent is still `ALLOWED`
  - `existingActionStatesIncludeEveryInFlightState`: checks the exact set over every `ActionIntentStatus`, and that every in-flight state is included
- Checked against the old behaviour: with the old CLAIMED/EXECUTING-only in-flight list put back temporarily, the class ran `Tests run: 7, Failures: 3`, with exactly the 3 new regression tests failing. The fix was then restored.

### Finding 3: the customer summary counts only MEND recoveries (`c6d5834`)

- New `Campaign.recoveredByMend()`: RECOVERED with `recovery_attribution = MEND`. The method name has no `get`/`is` prefix, so JPA (field access) and Jackson both ignore it. `AnalyticsQueryService` now uses it in place of its private copy, so analytics and the customer summary apply one rule.
- `CustomerSummaryDto`:
  - `recoveredCampaigns` and `totalRecoveredAmount` count MEND recoveries only
  - new fields, reported separately with the analytics names: `recoveredLateCaptureCount`, `amountRecoveredLateCapture`, `recoveredAttributionUnknownCount`, `amountRecoveredAttributionUnknown`
  - `totalFailedAmount` and `activeCampaigns` are unchanged
- Applies to both `GET /api/v1/customers` and `GET /api/v1/customers/{hash}`, which build the same summary.
- New `CustomerSummaryAttributionIntegrationTest` (2 tests), with one customer whose four campaigns are MEND ₹499.00, late capture ₹250.00, unknown ₹100.00 and active ₹300.00:
  - recovered = 1 / ₹499.00, late capture 1 / ₹250.00, unknown 1 / ₹100.00, failed total ₹1149.00, on both the profile and list endpoints
  - every recovered field equals the corresponding `AnalyticsQueryService.getAnalyticsOverview` field for the same campaigns
- No existing test changed.

### Finding 5: the webhook filter offers only real statuses (`f803138`)

- New `frontend/src/lib/webhookStatus.ts` defines `WEBHOOK_EVENT_STATUSES = ['RECEIVED', 'VERIFIED', 'PROCESSED', 'FAILED']`. The webhooks page filter is now `['ALL', ...WEBHOOK_EVENT_STATUSES]`, with `UNVERIFIED` and `DUPLICATE` removed.
- New tests (+3):
  - `webhookStatus.test.ts` reads `backend/.../WebhookEventStatus.java` and asserts the list is exactly its constants, in order, without UNVERIFIED or DUPLICATE
  - `webhooks/page.test.tsx` asserts the rendered `<select>` options are exactly `ALL, RECEIVED, VERIFIED, PROCESSED, FAILED`
- `npm test -- --ci` → `Tests: 42 passed, 42 total` (`Test Suites: 16 passed, 16 total`; was 39).
- `npm run build` → `✓ Compiled successfully`.
- Lint on the touched files: `webhooks/page.tsx` has the same 2 pre-existing errors before and after (`react-hooks/set-state-in-effect`). The new files add none.
- The backend is unchanged in this commit. The full backend suite was still run at `f803138` (row above).

### End state

- Backend: 660 → **671** tests (+5 JWT, +4 compliance, +2 customer summary), all with 0 failures, 0 errors and 0 skipped. No test was deleted and no existing assertion changed.
- Frontend: 39 → **42** jest tests, and `next build` succeeds.

### Out-of-scope findings (new, not changed)

1. **Attempt numbering in the duplicate check**: a new campaign has `attempt_count = 0`, but its first intent is attempt 1. The duplicate check (`attemptCount.equals(intent.getAttemptNumber())`) therefore matches only after execution has set `attempt_count` to the intent's attempt number (`ActionExecutionService` line 359). Before that, `ActionIntentService`'s idempotency key still returns the existing live intent, and the V13 index still holds.
2. **Customer summary amounts**: `totalFailedAmount` still leaves out campaigns with no resolvable amount, and there is no `amountUnknownCount` as in analytics Group 4.
3. **Frontend customer types and UI**: the `CustomerSummaryDto` type and the customer pages do not show the new late-capture and unknown fields yet (compare finding 4 above).
4. **`Badge`** still has a `DUPLICATE` case. It is harmless, and the landing page uses `DUPLICATE` as a demo-scenario id, not as a webhook status.
5. **`webhookStatus.test.ts`** reads a backend source file, so the frontend tests assume the monorepo layout.
