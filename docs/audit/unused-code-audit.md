# Unused-code audit (report only)

**Baseline for the cleanup** (`cd backend && ./mvnw test`, branch `audit/unused-code` from `hygiene/pre-m3` @ `9448d33`, 2026-10-11):
`[INFO] Tests run: 654, Failures: 0, Errors: 0, Skipped: 0` — `[INFO] BUILD SUCCESS`

Nothing was deleted or changed; this file is the only change.

## Method and limits

- **Classes:** for every class in `backend/src/main/java`, I counted files outside its own file that mention its simple name. Spring-annotated classes are wired by the container, so zero name references proves nothing for them; only un-annotated classes with zero main references are candidates.
- **Methods:** every method declared in main was counted for `name(` or `::name` call sites in main and test. Excluded: getters/setters (Jackson/JPA), `@Override`, mapped handlers, `@Scheduled`/`@Bean`/`@PostConstruct`/`@ExceptionHandler`/`@Pre*`. **Limit:** the scan is name-based, so a dead method that shares a common name with a live one (e.g. `process`, `handle`) is not detected. Repository methods have distinctive derived names and were checked precisely.
- **Enum values:** typed uses (`Enum.VALUE`), `case VALUE`, string literals in main, plus tests, migrations (`'VALUE'`), frontend and ai-service. Stored values were checked in the dev database (`select … group by`) and in CHECK constraints in V1–V15.
- **Endpoints:** every `@*Mapping` was matched against every `api.get/post/put/delete` path in `frontend/src`. Template paths (`${…}`) were resolved by hand.
- **Config:** keys in `application*.properties` vs `@Value` / `@ConditionalOnProperty` / `@ConfigurationProperties(prefix)`; `.env.example` keys vs `${VAR}`, `os.getenv`, `process.env`. The ai-service `.venv` was excluded.
- **Maven:** `./mvnw dependency:analyze`. **npm:** imports in `frontend/src` and config files. **Python:** imports in tracked `*.py` plus the Dockerfile.

## 1. Dead Java code

| item | location | evidence | bucket | risk if deleted | suggested action |
|---|---|---|---|---|---|
| `AuditEventType` enum (7 values) | `backend/src/main/java/com/mend/domain/enums/AuditEventType.java:3` | 0 references in main, test, frontend, ai-service; no JPA field uses it; audit `event_type` is a free string (no CHECK) | DELETE-SAFE | none (compile proves it) | delete; audit codes stay strings (see the M2 §9 table) |
| `ErrorCategory` enum (8 values) | `…/domain/enums/ErrorCategory.java:3` | 0 references anywhere in backend; the value names that appear in ai-service/frontend (`TIMEOUT`, `SYSTEM_ERROR`) are unrelated strings | DELETE-SAFE | none | delete |
| `ActionIntentRepository.findDueIntents` | `…/domain/repository/ActionIntentRepository.java:51` | 0 callers in main and test; superseded by `findDueClaimableIntents` (M2 U6) | DELETE-SAFE | none (Spring Data only validates declared queries) | delete |
| `AuditLogRepository.findByActorId` | `…/AuditLogRepository.java:23` | 0 callers in main and test | DELETE-SAFE | none | delete |
| `CampaignAttemptRepository.findFirstByCampaignIdOrderByAttemptNumberDesc` | `…/CampaignAttemptRepository.java:17` | 0 callers | DELETE-SAFE | none | delete |
| `CampaignRepository.findByPaymentId` | `…/CampaignRepository.java:22` | 0 callers; all paths use the tenant-scoped `findByMerchantIdAndPaymentId` | DELETE-SAFE | none; removes an unscoped lookup | delete |
| `CampaignRepository.findByMerchantIdAndSubscriptionId` (Optional) | `…/CampaignRepository.java:24` | 0 callers since U8 introduced `findAllByMerchantIdAndSubscriptionId` | DELETE-SAFE | none | delete |
| `CampaignRepository.findScheduledCampaignsByMerchant` (JPQL) | `…/CampaignRepository.java:38` | 0 callers | DELETE-SAFE | none | delete |
| `ClassificationResultRepository.existsByEventId` | `…/ClassificationResultRepository.java:21` | 0 callers | DELETE-SAFE | none | delete |
| `ComplianceDecisionRepository.existsByCampaignIdAndStatus` | `…/ComplianceDecisionRepository.java:19` | 0 callers | DELETE-SAFE | none | delete |
| `WebhookEventRepository.findByProcessingStatus` (List and Page overloads), `countByMerchantIdAndEventType` | `…/WebhookEventRepository.java:17,26,22` | 0 callers | DELETE-SAFE | none | delete |
| `OutboxEventRepository.findByAggregateTypeAndAggregateIdAndEventType` | `…/OutboxEventRepository.java:30` | 0 main callers, 4 test callers (outbox tests look rows up with it) | KEEP | breaks 4 tests | keep as test support, or move to a test helper later |
| `RedisStreamConsumer` (legacy consumer) | `…/consumer/RedisStreamConsumer.java:22` | not a bean (no stereotype; comment line 21 "Legacy consumer disabled"); its only reference is `ConsumerConsolidationTest.java:101`, which asserts it is **not** a bean | NEEDS-DECISION | production none; deleting it means changing that regression test (AGENTS.md: tests are not deleted without a reason) | owner: approve deleting the legacy consumer chain together with the assertion that guards it (replace the assertion with "exactly one consumer bean") |
| `WebhookEventHandler` + `DefaultWebhookEventHandler` | `…/handler/WebhookEventHandler.java`, `…/handler/DefaultWebhookEventHandler.java:15` | used only by `RedisStreamConsumer` (dead) and constructed by `RedisStreamPublisherConsumerTest.java:61`; comment line 14 "Legacy handler disabled" | NEEDS-DECISION | production none; that test exercises the legacy handler | decide together with `RedisStreamConsumer` |
| `WebhookPublisherRetryService` | `…/service/WebhookPublisherRetryService.java:36` | `retryFailedPublications()` has no caller in main and no `@Scheduled`; the bean exists but never runs. Only `RedisFailureAndRetryTest` calls it. Its own comment (line 38) says it is kept "for pre-outbox/orphaned records" | NEEDS-DECISION | production none today; if any environment has rows with `publish_status = FAILED` from before the outbox (V11), nothing republishes them either way | owner: confirm no pre-outbox FAILED rows exist (or republish them once), then delete it with `RedisFailureAndRetryTest` |
| `WebhookEventPublisher.publish(WebhookEvent)`, `RedisWebhookEventPublisher.publish`, `NoOpWebhookEventPublisher` | `…/publisher/WebhookEventPublisher.java:6`, `RedisWebhookEventPublisher.java:59`, `NoOpWebhookEventPublisher.java:11` | only caller in main is `WebhookPublisherRetryService` (above); ingestion uses the outbox (`publishOutbox`, line 131, comment line 102 "Webhook ingestion no longer uses this method"). The NoOp bean exists only to satisfy that service when `mend.redis.enabled=false` | NEEDS-DECISION | goes with the retry service; 4 publisher/consumer test classes call `publish` | delete only together with the retry service |
| Spring-wired classes with no name references (controllers, `@Configuration`, filters, `ActionScheduler`, `RazorpayConfiguration`, …) | see `class_refs` method | wired by the container (stereotype or `@Bean`), conditional where relevant | KEEP | — | none |

## 2. Legacy leftovers

| item | location | evidence | bucket | risk if deleted | suggested action |
|---|---|---|---|---|---|
| `CampaignStatus` legacy values `STRATEGY_ASSIGNED`, `WAITING`, `ACTION_EXECUTING`, `RETRY_SCHEDULED`, `PENDING_REVIEW`, `ESCALATED`, `MANDATE_INVALID`, `STOPPED` | `…/domain/enums/CampaignStatus.java:15-23` ("Legacy values retained for backward compatibility") | none is used as an enum anywhere in main or test. The hits are unrelated strings: `"ACTION_EXECUTING"` is an audit type (`ActionExecutionService.java:221`), `"STOPPED"` an agent-record status (`RecoveryOrchestratorService.java:151`). No state-machine edges. Dev database: 0 rows (states present: EXHAUSTED, RECOVERED, CANCELLED, ACTION_PENDING, ELIGIBLE, EXECUTING). `campaigns.current_state` has **no CHECK constraint**. Frontend `Badge.tsx:24` still styles `PENDING_REVIEW` | NEEDS-DECISION | if any environment still has a row with one of these values, Hibernate fails to load it (`@Enumerated(STRING)`) | owner: confirm no environment holds them (`select current_state, count(*) from campaigns group by 1`). Then a migration adds a CHECK on `current_state`, and the values plus the `Badge` case go |
| `ActionIntentStatus.PROCESSING` | `…/domain/enums/ActionIntentStatus.java:8` | never written by any code; only read in `ComplianceService.java:188` (duplicate-action check); allowed by `ck_action_intents_status` (V12:33); 0 dev rows | NEEDS-DECISION | needs a migration to drop it from the CHECK (V12 cannot be edited) | owner: remove with a V16 CHECK change, or keep as documented legacy |
| `ReviewQueueStatus.IN_REVIEW`, `OVERRIDDEN` | `…/ReviewQueueStatus.java:5,8` | never set in main; dev rows: APPROVED, PENDING only; frontend `Badge.tsx:30,36` | NEEDS-DECISION | rows in other environments (no CHECK on `review_queue.status`) | same check-then-CHECK approach |
| `WebhookEventStatus.PROCESSING`, `IGNORED`, `INVALID_SIGNATURE` | `…/WebhookEventStatus.java:6,9,10` | never written in main (the signature failure path rejects before persisting); dev rows: VERIFIED, PROCESSED; frontend filter `webhooks/page.tsx:19,22` offers PROCESSING and IGNORED | NEEDS-DECISION | rows in other environments; the frontend filter would offer a value the API rejects | decide with the frontend status work (§4) |
| "Phase N" names | tests `Phase5EndToEnd…`, `Phase6EndToEnd…`, `Phase10ActionScheduler…`, `Phase18Reliability…`, `Phase19Merchant…`, `Phase19_5_FullMock…`, `Phase20_RazorpayTestMode…`; `ai-service/test_phase18*.py`, `test_phase19_security.py`; comments `EventProcessingService.java:7` ("Phase 5 … Phase 6"), `NoOpWebhookEventPublisher.java:17` ("Phase 4 Webhook Gateway") | names refer to the pre-M1 phase plan, not to what the tests cover | NEEDS-DECISION | rename only (Surefire picks tests by `*Test`); git history and docs cite the names (`docs/m1`, `docs/m2`) | owner: rename to behaviour names in one mechanical commit, or keep for traceability |
| Test package `com.mend.client` holds payment-provider tests | `backend/src/test/java/com/mend/client/{PaymentProviderClientIntegrationTest, RazorpayPaymentProviderClientTest, RazorpayProviderSelectionTest}` | they test `com.mend.provider.*`; main `com.mend.client` now holds the AI classification clients | NEEDS-DECISION | none (move only) | move to `com.mend.provider` with the rename commit |
| Stale comment: "provider lease-expiry duplicate-call boundary" listed as a remaining gap | `README.md:104` | closed by M2 (ADR-002 Accepted, `M2ExitCriteriaTest.brief19`) | DELETE-SAFE (docs) | none | update the Limitations paragraph |

## 3. Configuration and dependencies

| item | location | evidence | bucket | risk if deleted | suggested action |
|---|---|---|---|---|---|
| Properties defined but never read | `application*.properties` | none found (every non-Spring-Boot key is read by `@Value`, `@ConditionalOnProperty` or `@ConfigurationProperties("razorpay")`) | — | — | — |
| Read with inline defaults but defined in no properties file: `mend.compliance.min-confidence`, `mend.recovery.strategy.min-confidence`, `mend.consumer.poll-interval-ms`, `mend.scheduler.{batch-size, default-retry-delay-minutes, enabled, lease-duration-minutes, poll-interval-ms, worker-id}` | `@Value("${…:default}")` in main | defaults work; undocumented | KEEP | — | list them in `application.properties` with their defaults (the confidence ones are M3-owned) |
| `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` | `.env.example:17` | no reference by name, but relaxed binding maps it onto `spring.jpa.hibernate.ddl-auto`; it is effective if exported | NEEDS-DECISION | none if removed (the default is already `validate`); a footgun if anyone sets it to `update` (invariant 10) | owner: remove it, or keep it pinned to `validate` with a warning comment |
| Variables the code reads that are missing from `.env.example`: `SPRING_PROFILES_ACTIVE` (default **dev**, so demo and dev seed are on), `RAZORPAY_SINGLE_TENANT_MERCHANT_ID` (needed in razorpay mode), `RAZORPAY_API_BASE_URL`, `RAZORPAY_CONNECT_TIMEOUT_MS`, `RAZORPAY_READ_TIMEOUT_MS`, `MEND_CORS_ALLOWED_ORIGINS`, `MEND_OUTBOX_{BATCH_SIZE, POLL_INTERVAL_MS, PUBLISHER_ENABLED, RETRY_BASE_DELAY_SECONDS, RETRY_MAX_DELAY_SECONDS}`, `MEND_REDIS_RETRY_MAX_ATTEMPTS`, `MEND_AI_CONNECT_TIMEOUT_MS`, `MEND_AI_READ_TIMEOUT_MS`, `NEXT_PUBLIC_API_URL`, `POSTGRES_DB_URI` (ai-service) | `application.properties`, `frontend/src/lib/api.ts`, `ai-service/agent/checkpointer.py` | read by code; not in `.env.example` | KEEP | — | add them to `.env.example` (the AI ones are M3-owned) |
| Maven "unused declared": `spring-boot-starter-{webmvc,data-jpa,data-redis,test,webmvc-test}`, `postgresql` (runtime), `flyway-database-postgresql` | `backend/pom.xml` | `dependency:analyze` false positives: starters only aggregate, the JDBC driver and the Flyway database module are loaded at runtime | KEEP | app or tests stop working | none |
| `com.fasterxml.jackson.core:jackson-databind` (Jackson 2) | `backend/pom.xml:41` | used by `AnalyticsQueryService.java:3-4` and 4 tests; the rest of main uses Jackson 3 (`tools.jackson`, 17 files) | LATER-MILESTONE (M5 analytics) | breaks analytics and 4 tests | drop it when analytics moves to the shared amount resolver (§5) |
| npm packages | `frontend/package.json` | deps `next`, `react`, `react-dom` are used. Dev deps are used by tooling: Tailwind (`postcss.config.mjs`), Jest + Testing Library (`jest.config.ts`, `*.test.tsx`), ESLint, TypeScript, `@types/*` | KEEP | — | none |
| Python `python-dotenv`, `PyYAML` | `ai-service/requirements.txt:30,31` | never imported in tracked `*.py` (no `dotenv`, no `yaml`) | M3-OWNED | — | M3 decides |
| Python `psycopg`, `psycopg-pool`, `langchain-core`, `uvicorn` | `ai-service/requirements.txt` | not imported directly: pins for `langgraph-checkpoint-postgres` / `langchain-*`; `uvicorn` is the Dockerfile `CMD` | M3-OWNED (KEEP in effect) | — | M3 decides |

## 4. Frontend

| item | location | evidence | bucket | risk if deleted | suggested action |
|---|---|---|---|---|---|
| Components, pages, API client | `frontend/src/components/*`, `src/app/*`, `src/lib/api.ts` | every component is imported by at least one page; `api.ts` is a generic `get/post/put/delete` client, so there are no per-endpoint functions to go stale | KEEP | — | none |
| Frontend calls to missing endpoints | — | none: all 28 call sites match a backend mapping (review approve/reject and campaign pause/terminate go through template paths) | — | — | — |
| Backend endpoints the frontend never calls: `GET /api/v1/auth/me`, `GET /api/v1/system/health/operational`, `GET /health`, `POST /api/v1/campaigns/{id}/resume`, `GET /api/v1/audit/campaign/{id}`, `GET /api/v1/recovery/actions/{id}`, `GET /api/v1/reviews/{id}`, `GET /api/v1/webhooks/{id}`, member management (4 endpoints, `MerchantMemberController.java:26-53`) | controllers | no frontend call; some have backend tests (`CrossTenantReadIsolationIntegrationTest`, `CorsAndSecurityIntegrationTest`, `Phase19…`) | KEEP | they are the public API surface (operators, scripts, future UI); deleting removes features | none; consider OpenAPI docs |
| `POST /api/v1/agent/orchestrate`, `GET /api/v1/agent/timeline` | `AgentOrchestrationController.java:32`, `AgentDecisionQueryController.java:45` | no frontend call; covered by `AiAgentIntegrationTest`, `CrossTenantReadIsolationIntegrationTest` | M3-OWNED | — | M3 |
| `POST /api/v1/webhooks/razorpay` | `WebhookController.java:31` | called by Razorpay, not the frontend | KEEP | ingestion stops | none |
| **Missing M2 states in the UI**: no `AWAITING_PAYMENT` or `NEEDS_RECONCILIATION` anywhere in `frontend/src` | `src/app/actions/page.tsx:22` (filter list also lacks `EXECUTING`), `src/app/campaigns/[id]/page.tsx:148-150` (pending/success checks), `src/components/common/Badge.tsx` (falls through to grey default) | `grep -rn AWAITING_PAYMENT frontend/src` → none | NEEDS-DECISION | — (missing, not dead) | owner: schedule the frontend status mapping (ADR-002 "frontend display mapping … tracked, not M2") |
| `'COMPLETED'` intent status check | `src/app/campaigns/[id]/page.tsx:148` | no intent status `COMPLETED` exists in the backend; `COMPLETED` is used only as a UI step label (line 157) | DELETE-SAFE | none | drop the `\|\| ai.status === 'COMPLETED'` comparison in the frontend status commit |
| Badge cases for values the backend never emits (`PENDING_REVIEW`, `IN_REVIEW`, `OVERRIDDEN`) | `Badge.tsx:24,30,36` | see §2 | NEEDS-DECISION | none in the UI | remove with the enum decision |
| Demo page | `src/app/demo/page.tsx` | backend `/api/v1/demo` exists only with `mend.demo.enabled=true` (hygiene); the page 404s in every other profile | NEEDS-DECISION | — | owner: hide the page when the demo is off, or keep it dev-only |
| `frontend/README.md` (create-next-app boilerplate) | `frontend/README.md` | says nothing about MEND; nothing links to it | DELETE-SAFE | none | replace with two lines pointing to the root README |
| `frontend/AGENTS.md` / `CLAUDE.md` | `frontend/` | written by `next dev` ("re-added by next dev"; `node_modules/next/dist/server/lib/generate-agent-files.js`) | KEEP | regenerated if removed | none |

## 5. Duplicate logic

| item | locations | evidence | bucket | risk if deleted | suggested action |
|---|---|---|---|---|---|
| Original-amount parsing ×3 | (a) `WebhookAmountResolver.extractMoneyMinor` (strict: positive integer minor units + INR, M1); (b) `WebhookAmountResolver.extractAmountFromWebhook` `:61` / `resolveAmount` `:35` (BigDecimal for display; accepts any `asLong`, no currency check), used by `PaymentController.java:135`, `HumanApprovalService.java:340`; (c) `AnalyticsQueryService.extractAmountFromWebhook` `:467` (Jackson 2, `double`, **returns a fabricated `1000.00`** when the amount is missing, lines 468-488) | three implementations of one rule with different validation; (c) violates invariants 1 (double for money) and 3 (fabricated amount) inside analytics | LATER-MILESTONE (M5 analytics) for (c); NEEDS-DECISION for (b) | analytics and display amounts change (they would become explicit "unknown") | (c) before M5: use (a) and report unknown amounts as unknown; (b) derive display from (a) |
| Membership / tenant check pasted into services | `HumanApprovalService.java:365` (`validateMembership`), `AnalyticsQueryService.java:462`, `ActionIntentService.java:250`, `AuditService.java:159`, `CampaignLifecycleService.java:263`, `CampaignQueryService.java:132`, `ComplianceService.java:218`, `RecoveryActionQueryService.java:75`, `RecoveryStrategyService.java:161`, `WebhookQueryService.java:75`, `MerchantConfigService.java:118`, `MerchantMemberService.java:195`, `AgentDecisionQueryController.java:52`; `SecurityFilter.java:69` does the header check | the same `currentUser != null && !isSystemAdmin && !isMemberOfMerchant` rule in about 12 places. Most copies **pass when `currentUser == null`** (internal callers); `MerchantConfigService` / `MerchantMemberService` do not | NEEDS-DECISION | a behaviour change if unified naively (null-user semantics differ) | owner: one tenant guard with explicit "system call" vs "user call"; separate security-review item |
| Payment-link reference test (`startsWith("plink_")`) ×2 | `ExecutionReconciler.java:232`, `DefaultPaymentReconciliationService.java:633` | same rule twice | NEEDS-DECISION (small) | none | one helper on the provider types, e.g. `PaymentLinkSnapshot.isLinkId` |
| Captured/failed status interpretation | `RazorpayEvidenceMapper` (single interpreter, M1 U5) | no second implementation found (`grep '"captured"'` hits only fixtures/demo payload builders) | — | — | — |

## 6. Scripts and docs

| item | location | evidence | bucket | risk if deleted | suggested action |
|---|---|---|---|---|---|
| `scripts/verify.sh` | `scripts/verify.sh` | referenced by `README.md`; runnable (needs an existing `ai-service/.venv`) | KEEP | — | none |
| `scripts/record_razorpay_contracts.sh` | `scripts/` | referenced by M1/M2 docs and ADR-002 (`--cancel-link`) | KEEP | — | none |
| **10 broken README links** | `README.md:80,90,98,108` (+ links to ARCHITECTURE, SECURITY, RELIABILITY) | `docs/ARCHITECTURE.md`, `SECURITY.md`, `RELIABILITY.md`, `DEMO.md`, `SETUP.md`, `PROJECT_STRUCTURE.md`, `PHASE21_PACKAGE_INDEX.md`, `PHASE20_FORENSIC_FINDINGS.md`, `CV_BULLETS.md`, `INTERVIEW_GUIDE.md` do not exist | DELETE-SAFE (docs) | none | rewrite the README's doc index to point at `docs/m1`, `docs/m2`, `docs/adr`, `docs/hygiene` |
| `docs/health-check*.{md,txt}` | `docs/` | pre-M1 health check (2026-10-05); historical evidence cited by M1 docs | KEEP | breaks references in `docs/m1` | none |
| ai-service benchmark JSONs | `ai-service/performance_benchmark_results.json`, `recovery_effectiveness_benchmark.json` | referenced by `test_phase18_reliability.py` and `docs/health-check.md` | M3-OWNED | — | M3 |

## 7. Tests

| item | location | evidence | bucket | risk if deleted | suggested action |
|---|---|---|---|---|---|
| `ConsumerConsolidationTest.testSingleActiveConsumerInContext` (legacy half) | `…/consumer/ConsumerConsolidationTest.java:101` | asserts the legacy `RedisStreamConsumer` is not a bean | NEEDS-DECISION | it guards the consolidation; keep the "one consumer bean" half | rewrite with the §1 consumer decision |
| `RedisStreamPublisherConsumerTest` | `…/publisher/RedisStreamPublisherConsumerTest.java` (5 tests) | builds the legacy `DefaultWebhookEventHandler` and calls `publish(WebhookEvent)`, which production does not use | NEEDS-DECISION | loses Redis stream coverage unless the outbox path covers it (`ConsumerConsolidationTest`, `RedisWebhookEventConsumerTest` do) | re-point at the outbox transport |
| `RedisFailureAndRetryTest` | `…/publisher/RedisFailureAndRetryTest.java` (2 tests) | tests `WebhookPublisherRetryService`, which never runs in production | NEEDS-DECISION | none for production | goes with the retry-service decision |
| ai-service tests | `ai-service/test_*.py` | AI path | M3-OWNED | — | M3 |
| Duplicated tests | — | none found with identical scope. Overlap exists by design: `ExecutionSafetyScenarioTest` (RED scenarios) vs `M2ExitCriteriaTest` (exit criteria) | KEEP | — | none |

## M3-OWNED (AI path, not proposed for deletion)
`ai-service/**` (all code, tests, benchmarks, requirements); backend `agent/DefaultAgentDecisionEngine`, `client/AiClassificationClient`, `client/FastApiClassificationClient`, `ClassificationService`, `RecoveryOrchestratorService`, `AgentOrchestrationController`, `AgentDecisionQueryController`, `AgentDecisionRecord` (+ repository, DTOs), `dto/ai/**`, `RecommendedAction` (no typed main use outside `ClassificationService.java:74`), confidence properties (`mend.compliance.min-confidence`, `mend.recovery.strategy.min-confidence`); frontend `AgentDecisionStack`.

## LATER-MILESTONE
- **M5 analytics:**
  - `AnalyticsQueryService` amount parsing (fabricated 1000.00, `double`, Jackson 2).
  - Analytics counts every RECOVERED campaign, including `recovery_attribution = ORIGINAL_PAYMENT_LATE_CAPTURE`.
  - Metrics registration (the §9 metric column).
- **CI / deployment:** no CI config exists; `scripts/verify.sh` is the only gate.

## Counts per bucket

| bucket | table rows |
|---|---|
| DELETE-SAFE | 15 (the deletion plan below groups them into 4 commits) |
| NEEDS-DECISION | 19 |
| KEEP | 14 |
| M3-OWNED | 5 (+ the M3 list above) |
| LATER-MILESTONE | 2 (+ the list above) |
| informational, no finding | 3 (properties defined-but-unread: none; frontend calls to missing endpoints: none; second status interpreter: none) |

## Proposed deletion plan (DELETE-SAFE only; each group is one small commit, full suite after each)

1. **`cleanup: remove unused enums`**: `AuditEventType.java`, `ErrorCategory.java`.
   - Why safe: 0 references in backend, frontend and ai-service; no JPA mapping; no CHECK constraint names them.
   - Proof: compile + `./mvnw test` stays at 654 / 0 / 0 / 0.
2. **`cleanup: remove unused repository methods`**: `ActionIntentRepository.findDueIntents`, `AuditLogRepository.findByActorId`, `CampaignAttemptRepository.findFirstByCampaignIdOrderByAttemptNumberDesc`, `CampaignRepository.{findByPaymentId, findByMerchantIdAndSubscriptionId, findScheduledCampaignsByMerchant}`, `ClassificationResultRepository.existsByEventId`, `ComplianceDecisionRepository.existsByCampaignIdAndStatus`, `WebhookEventRepository.{findByProcessingStatus ×2, countByMerchantIdAndEventType}`.
   - Why safe: 0 callers in main or test; Spring Data only parses declared methods at startup, so removing them removes startup work.
   - Proof: context startup in every `@SpringBootTest`, plus `PaymentReconciliationTest`, `CorrelationTest`, `ActionIntentTransitionsTest`, `OneInFlightPerInvoiceTest`, `M2ExitCriteriaTest`; the full suite stays at 654.
3. **`cleanup: frontend dead status check`**: `frontend/src/app/campaigns/[id]/page.tsx:148` (drop `ai.status === 'COMPLETED'`).
   - Why safe: no backend status `COMPLETED`.
   - Proof: `npm run test` (the campaign page has no unit test, so add a small render test with SUCCEEDED/FAILED intents if wanted) and `npm run build`.
4. **`docs: README index and limitations`**: `README.md` (fix the 10 broken links, drop the closed lease-expiry gap) and `frontend/README.md` (replace boilerplate).
   - Why safe: docs only.
   - Proof: a link check (`grep -oE '\]\(([^)]+)\)' README.md` → every target exists).

NEEDS-DECISION items (legacy enum values, the legacy consumer/handler/retry chain and their tests, test renames, the tenant-guard consolidation, the amount-parser consolidation, the missing frontend states) are **not** in this plan. Each needs an owner decision first; several need a migration (CHECK constraints) or would change existing tests.
