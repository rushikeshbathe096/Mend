# AGENTS.md — rules for any coding agent working on MEND

MEND is a financial workflow system (revenue recovery on Razorpay). Correctness before intelligence.

## Current milestone: M3 complete — next: M4 (needs design pack)
M3 (honest intelligence) is complete: ADR-003 Accepted, final run backend 940 / 0 / 0 / 0, frontend 62, ai-service 90
(`docs/m3/m3-final-test-run-summary.txt`), summary `docs/m3/M3-summary.md`.
M1 (provider correctness) is complete: tag `m1-complete`, ADR-001 Accepted.
M2 (execution safety) is complete: ADR-002 Accepted, final run 647 / 0 / 0 / 0 (`docs/m2/m2-final-test-run-summary.txt`), summary `docs/m2/M2-summary.md`.
Only work on the unit you were given (`docs/m2/agent-prompts/U*.md`). Do not start M4+ work without its design pack. Write anything else you notice under "Out-of-scope findings" in your final message.

Out of scope in M2: debit wiring, AI service, CI/deployment, metrics registration, Redis changes, new dependencies (ask first), frontend changes.

## Non-negotiable invariants
1. Money is `long` minor units (paise). Never `double`/`float`. No `BigDecimal` in provider code.
2. Only provider evidence (`captured`) establishes recovery. A 2xx, a created link, or an `authorized` payment is NOT recovered money.
3. Never fabricate provider data (ids, statuses, amounts, confidence, model versions). Missing data → explicit unknown.
4. A timeout or 5xx on a state-changing provider call is `OutcomeUnknown`, never "failed".
5. Never auto-retry a POST to the provider. Resend only via the reconciler's SAFE_RETRY, only for actions where `supportsIdempotentResend` is true.
6. Only call Razorpay endpoints listed in `docs/m1/M1-provider-correctness.md` §6 (or ones verified from official docs AND recorded in test mode). Never invent an endpoint, header, or field.
7. Compliance is re-checked immediately before execution, in the same transaction as the dispatch fence. Never remove or weaken it.
8. Webhook payloads, provider responses, and LLM output are untrusted.
9. Every state transition writes an audit record (ids and codes only, no PII, no secrets).
10. Flyway owns the schema (`ddl-auto=validate`). Never edit an existing migration; add V12+.

### M2 invariants
11. After creation, intent status changes ONLY through `ActionIntentTransitions` conditional updates (fenced by status, and by `claim_token` for worker-owned transitions).
12. No provider call without an applied `dispatch` (CLAIMED→EXECUTING) transition.
13. Possibly-dispatched work (EXECUTING) is never returned to READY by lease expiry.
14. At most one in-flight intent (CLAIMED, EXECUTING, NEEDS_RECONCILIATION, AWAITING_PAYMENT) per (merchant, invoice_key) — enforced by the database.
15. Campaign-level (original payment) events never finalize a recovery intent.

### M3 invariants (ADR-003)
16. The payment flow makes no ai-service call.
17. LLM output is a suggestion: only the deterministic ReplyPolicy acts on it, never with a financial action.
18. A model id is recorded only as the provider reported it; without a model there is no output ("model unavailable").

## Tests
- Run: `cd backend && ./mvnw test`
- Baseline for M2: 519 run / 0 failures / 0 errors / 1 skipped — `docs/m1/m1-final-test-run-summary.txt` (raw logs are local-only).
- Never delete or weaken an existing test. If a legitimate behaviour change breaks one: keep it, change the assertion, put `// M2 behaviour change: <reason>, see ADR-002` above it, and add a regression test.
- Concurrency tests use real PostgreSQL, real threads and latches; simulate time with SQL updates, not long sleeps.
- Test databases: every `./mvnw test` run creates its own empty database `mend_test_<epoch>_<random>` on the docker-compose Postgres (port 5433), Flyway migrates it from V1, and it is dropped when the run ends (`com.mend.testsupport.IsolatedTestDatabase`, auto-registered via `META-INF/services` + `junit-platform.properties`). Stale `mend_test_*` databases older than 24 h are dropped at the next start. `application-test.properties` has no fallback URL, and the extension also sets `spring.datasource.*` / `spring.flyway.*` as system properties (they outrank an exported `SPRING_DATASOURCE_URL`), so tests never touch the dev database `mend`. Details: `docs/hygiene/pre-m3-hygiene.md`. Override the server with `-Dmend.test.db.server-url=jdbc:postgresql://host:port/postgres` (also `mend.test.db.user` / `.password`, or `MEND_TEST_DB_*` env vars). Never hand-edit a database to make tests pass. Classes within one run still share that run's database, so keep "park other rows" / own-row cleanup patterns.
- Provider contract fixtures: `backend/src/test/resources/razorpay/recorded/` (real) and `.../doc-derived/` (with `_source`).

## Code style
- Java 21: records, sealed interfaces, exhaustive `switch`.
- Domain (`com.mend.provider`) has no Spring, HTTP, JPA, or Razorpay imports.
- No `*Utils`, `*Manager`, `*Helper` grab-bag classes.

## Final message format (every unit)
1. Files changed
2. Tests: command + exact counts copied from output
3. Acceptance criteria: each met / not met
4. Deviations and why
5. Out-of-scope findings
