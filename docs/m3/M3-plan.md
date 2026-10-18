# M3 — implementation plan and acceptance checklist

Branch `m3/honest-intelligence` from local `main` @ `1c7fb7d` (`ci-v1`). Each unit: plan in
`docs/next-phase/progress.md` → small commits → full verify → regression test + revert proof per behaviour change →
fresh-subagent review → log. Fixed rules (not changeable): AI never on the payment hot path; AI never decides a financial
action; no fabricated confidence, model versions, scores or fallbacks; one AI task only.

## Units (refined from the inventory)

- **U2.1 Deterministic failure taxonomy.** `com.mend.taxonomy` (pure Java, no Spring):
  - `PaymentFailureClass` (9 classes, ADR-003 §1; distinct from the legacy `domain.enums.FailureClass`), `ProviderFailure(code, reason, source)`, `FailureTaxonomy.classify(...)`
    returning `FailureClassification(class, matchedOn, rule, docUrl, tableVersion)`.
  - The table maps every documented Razorpay reason that has a recovery meaning; each row carries the docs URL.
  - Fallback by `source`, only when no reason is sent: `gateway` / `razorpay` → `GATEWAY_ERROR` (documented as
    transient). A present but unmapped reason, and everything else → `UNCLASSIFIED`.
  - Tests: one per mapped reason (parameterized from the table), unmapped → UNCLASSIFIED, the source fallback, the
    doc-derived fixture.
  - Not wired yet.
- **U2.2 AI off the hot path.**
  - `ClassificationService` uses `FailureTaxonomy`; `DefaultEventProcessingService.extractFailureDetails` extracts
    `error_code`, `error_reason`, `error_source` separately (it never read the source).
  - V19 also adds `classification_results.taxonomy_version`; deterministic rows leave `model_version` NULL.
  - Compliance blocks `UNCLASSIFIED` and any non-taxonomy (legacy) class, in place of the `UNKNOWN` and LOW_CONFIDENCE
    blocks. Legacy campaigns re-evaluated (approval, pre-dispatch re-check) fail closed. Regression tests for both.
  - `CampaignLifecycleService:234` stops copying confidence to the campaign.
  - `RecoveryOrchestratorService` drops the agent step: strategy → compliance → intent.
  - V19: `classification_results.confidence` becomes nullable (rows kept).
  - Strategy and eligibility are keyed by class, and the confidence gates in eligibility, strategy and compliance are
    removed in this unit. Reason: a deterministic class has no confidence, so the gates would block every campaign.
    This moves that part of U2.3 forward; recorded as a DECISION.
  - The ai-service timeout no longer touches payments.
  - Proof: payment-flow tests with ai-service unreachable (`mend.ai.base-url=http://127.0.0.1:9`) and with no client
    bean give identical results.
- **U2.3 Delete theatre.**
  - Backend: remove `agent/*`, `AgentOrchestrationController`, `AgentDecisionQueryController`, `dto/ai/Agent*`, the
    classify/orchestrate client, AI fields on the review, payment and start-recovery DTOs, and `RecommendedAction`
    storage of AI output.
  - Remove demo fake agent steps and dev seed fake confidences (`DevDataInitializer:275,304,315,333`).
  - Also: `AgentContext:43`, `AgentDecision:49`, `AnalyticsQueryService:265-287` (AI confidence metrics), frontend
    confidence displays (`dashboard:323`, `campaigns:263-309`, `analytics:240-252`), and the settings label that calls
    `escalationThreshold` an AI confidence (`settings:181-192`).
  - Migration only where needed (no V1–V18 edit). `agent_decision_records` stays as a legacy table (no writer, no API).
  - Frontend: remove `AgentDecisionStack`, the agent tab, agent confidence and payment agent decisions; show the
    failure class and the Razorpay reason.
  - Tests that asserted theatre are rewritten to assert the deterministic behaviour; the count never drops.
- **U2.4 Customer-reply parsing.**
  - V20: `customer_replies` (merchant_id, campaign_id, text ≤ 2000, status, attempts, next_attempt_at, created_at) and
    `reply_suggestions` (reply_id, intent, promised_date, abstain, valid, validation_error, model_id, model_version,
    prompt_version, created_at).
  - API `POST/GET /api/v1/campaigns/{id}/replies` (TenantGuard user call).
  - `ReplyParsingJob` (scheduled, `SKIP LOCKED`, backoff, attempt cap).
  - Strict validator.
  - `ReplyPolicy` (each effect audited; abstained/invalid → no effect):
    - OPT_OUT → `campaigns.contact_opted_out_at`; compliance block `CUSTOMER_OPTED_OUT` for new intents and at the
      pre-dispatch re-check. Pending intents end through the existing fenced path; EXECUTING / AWAITING are untouched.
      `DELETE /api/v1/campaigns/{id}/opt-out` (MERCHANT_ADMIN, audited) clears it.
    - ALREADY_PAID → AWAITING_PAYMENT link intents of the campaign get `reconcile_after = now` (brings forward the
      reconciler's own evidence lookup; that branch never resends; later steps keep the reconciler's own schedule). NEEDS_RECONCILIATION is not touched, so SAFE_RETRY is never reply-triggered. No link
      intent → review `CUSTOMER_SAYS_PAID`. Never RECOVERED. Test: a reply never causes a SAFE_RETRY or a provider POST.
    - DISPUTE → review `CUSTOMER_DISPUTE` (appends to an open review, item 3 of the bug sweep).
    - Others → shown only.
  - The campaign must belong to the caller's merchant (not only `TenantGuard`). One suggestion per reply: re-parsing
    happens only for FAILED replies, by a human retry.
  - Retention note: 90 days, purge job M5-owned.
- **U2.5 Evaluation.**
  - `docs/m3/eval/replies-v1.jsonl` (SYNTHETIC-labelled) and `docs/m3/eval/LABEL-GUIDE.md`.
  - `ai-service/eval/run_eval.py` writes dataset version, model id, prompt version, per-class P/R/F1, macro F1,
    confusion matrix, invalid-output rate and abstention rate.
  - Keyword baseline run and recorded. The LLM runs only if an API key exists in the environment; otherwise TBD.
- **U2.6 ai-service reduction.**
  - Keep `/health` and `/api/v1/parse_reply`.
  - Delete LangGraph agents, MCP tools, the checkpointer, benchmarks, and the agent/classification tests (replaced by
    parse_reply tests).
  - Drop unused deps (langgraph*, psycopg*, langchain-google-genai if not the chosen provider, python-dotenv, PyYAML);
    keep exactly what is imported.
  - pytest green; `scripts/ci/ai-service.sh` and the CI ai-service job still green (requirements reduced).
- **U2.7 Close-out.** Full suites, `M3-summary.md` (≤ 15 lines), ADR-003 Accepted with evidence, checklist ticked,
  AGENTS.md milestone line.

## §50 acceptance checklist (derived from the M3 rules; the brief itself is not in the repository)

- [x] 1. The payment flow makes zero ai-service calls (proof test with ai-service unreachable). — U2.2 Evidence: `31a1949`, `ecf1d83` (AiOffHotPathTest).
- [x] 2. No AI output can create, stop, hold or approve an action intent. — U2.2/U2.3 Evidence: `31a1949`, `1c2363f`, `3f6209d` (ReplyParsingIntegrationTest: no POST, no SAFE_RETRY, no RECOVERED).
- [x] 3. No hardcoded confidence, invented model version, synthetic fallback or risk score remains in backend, ai-service or frontend (grep evidence). — U2.3/U2.6 Evidence: `1c2363f`, `f005c62`, `3aedd1d`, `53ec57d`; grep at U2.7: only legacy entity columns, the LOW_CONFIDENCE enum value for history, and comments.
- [x] 4. Failure taxonomy is deterministic, table-driven, cites the Razorpay docs per mapping, undocumented → UNCLASSIFIED. — U2.1 Evidence: `314bf9e`, `0014eed`.
- [x] 5. Historical rows kept; no V1–V18 edit; new migrations never rewrite data. — U2.2–U2.4 Evidence: V19–V21 schema-only; `FreshDatabaseMigrationTest` applies 21, validate OK.
- [x] 6. Exactly one AI task (reply parsing), asynchronous, schema-validated, invalid recorded as invalid. — U2.4 Evidence: `8fe7e0c`, `3f6209d`, `3ff511f`.
- [x] 7. Deterministic reply policy; LLM output never triggers a financial action; ALREADY_PAID never marks recovered. — U2.4 Evidence: `3f6209d`, `3ff511f`.
- [x] 8. Model id/version from the provider response; no key → "model unavailable", no output. — U2.4/U2.6 Evidence: `8fe7e0c`, `be25b4b` (test_parse_reply, HttpReplyParserClientTest).
- [x] 9. Replies are PII: tenant-scoped API (TenantGuard), minimal storage, retention noted. — U2.4 Evidence: `3f6209d` (TenantGuard table +4, no text in logs/audit; retention in ADR-003).
- [x] 10. Eval dataset versioned + labelled SYNTHETIC; baseline metrics recorded; model metrics only if run. — U2.5 Evidence: `2bc479d`, `e2c72c1` (model TBD: no key).
- [x] 11. ai-service reduced to parse_reply + health; unused deps removed; pytest green. — U2.6 Evidence: `53ec57d`, `be25b4b` (90 passed, fresh venv).
- [x] 12. Test counts never drop; backend 0/0/0; frontend lint 0 errors, tests, build; ai-service pytest. — all Evidence: 813→940 / 53→62 / 51→90 (`docs/m3/m3-final-test-run-summary.txt`).
- [x] 13. ADR-003 Accepted with evidence; M3-summary.md; AGENTS.md milestone updated. — U2.7 Evidence: this close-out commit.
