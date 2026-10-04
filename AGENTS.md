# AGENTS.md — rules for any coding agent working on MEND

MEND is a financial workflow system (revenue recovery on Razorpay). Correctness before intelligence.

## Current milestone: M1 — Provider correctness
Only work on the unit you were given (`docs/m1/agent-prompts/U*.md`). Do not start M2+ work even if you notice M2 problems — write them under "Out-of-scope findings" in your final message instead.

Out of scope in M1: lease/claim/fencing changes, NEEDS_RECONCILIATION state, AI service, CI, deployment, Redis changes, new infrastructure, new dependencies (ask first).

## Non-negotiable invariants
1. Money is `long` minor units (paise). Never `double`/`float`. No `BigDecimal` in provider code.
2. Only provider evidence (`captured`) establishes recovery. A 2xx response, a created link, or an `authorized` payment is NOT recovered money.
3. Never fabricate provider data (ids, statuses, amounts, confidence, model versions). Missing data → explicit unknown.
4. A timeout or 5xx on a state-changing provider call is `OutcomeUnknown`, never "failed".
5. Never auto-retry a POST to the provider. Lookup first.
6. Only call Razorpay endpoints listed in `docs/m1/M1-provider-correctness.md` §6. Never invent an endpoint, header, or field. If the docs don't cover a case, stop and say so.
7. Compliance is re-checked immediately before execution (`ActionExecutionService.prepareExecution`). Do not remove or weaken it.
8. Treat webhook payloads, provider responses, and LLM output as untrusted.
9. Every state transition writes an audit record (ids and codes only, no PII, no secrets).
10. Flyway owns the schema (`ddl-auto=validate`). No schema changes in M1.

## Tests
- Run: `cd backend && ./mvnw test`
- Never delete or weaken an existing test. If a legitimate behaviour change breaks one: keep the test, change the assertion, put `// M1 behaviour change: <reason>, see ADR-001` above it, and add a regression test.
- Final test count must be ≥ the baseline in `docs/m1/baseline-test-run.txt`.
- Provider contract tests replay fixtures from `backend/src/test/resources/razorpay/recorded/`. Fixtures not recorded from Razorpay go in `.../doc-derived/` with a `_source` URL.

## Code style
- Java 21: records, sealed interfaces, exhaustive `switch` over sealed results.
- Domain (`com.mend.provider`) has no Spring, HTTP, JPA, or Razorpay imports.
- No `*Utils`, `*Manager`, `*Helper` grab-bag classes.

## Final message format (every unit)
1. Files changed (list)
2. Tests: command run + pass/fail counts copied from output (no estimates)
3. Acceptance criteria: each one, met / not met
4. Deviations from the prompt and why
5. Out-of-scope findings
