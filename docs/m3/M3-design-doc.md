# DESIGN DOC — M3 Honest intelligence

Status: accepted for implementation by the orchestrator (U2.0). Inventory: `docs/m3/M3-analysis.md`. ADR: ADR-003.

## Problem
MEND's "AI" sits on the payment hot path and makes up evidence. Every new payment failure makes two synchronous calls to
ai-service, each with a 3 s timeout, inside the classification transaction. Confidences and model versions are hardcoded constants
or LLM self-reports. A failed call is replaced with a fabricated fallback (`UNKNOWN`, 0.30, "v1.0.0-fallback"). Those
numbers then gate eligibility (0.50), strategy (0.80) and compliance (0.80), and a "six-agent" result can stop a recovery
or hold it for review. This breaks invariant 3 (never fabricate confidence or model versions). It also lets AI decide
financial actions.

## Current behaviour
See `M3-analysis.md` §1–§4. Failure classification is AI or keyword output with no mapping to Razorpay's documented error
reasons; ai-service downtime changes the outcome (fallback UNKNOWN → EXHAUSTED); `agent_decision_records` store fabricated
decisions shown in the UI as "AI Agent Decisions".

## Evidence
- Code: file:line inventory in `M3-analysis.md`.
- Razorpay error reasons and sources: https://razorpay.com/docs/errors/payments/list/ and https://razorpay.com/docs/errors/
  (fetched 2026-10-18); doc-derived fixture `payment_failed_entity.json`.
- Baseline tests: backend 813 / 0 / 0 / 0 (Phase 1 close-out), frontend 53, ai-service 51.

## Requirements
1. AI is never on the payment hot path: webhook → deterministic taxonomy → strategy → compliance → intent, with zero
   ai-service calls; behaviour is identical with ai-service stopped.
2. AI never decides a financial action: no AI output can create, approve, schedule, resend or finalize an action intent,
   and none can mark money recovered. The one AI-derived effect that withholds action (OPT_OUT) is a fail-closed
   compliance block, checked at dispatch like every compliance rule (invariant 7), reversible only by a human.
3. No fabricated confidence, model versions, scores or fallbacks anywhere. Missing data is explicitly unknown.
4. One AI task only: parsing a customer's reply into a structured *suggestion*, asynchronously.
5. Historical rows are kept, never rewritten; schema changes only through new migrations (V19+).
6. Evaluation is reproducible and honest: versioned dataset, deterministic baseline, model metrics only when actually run.

## Invariants
AGENTS.md 1–15 unchanged. Added for M3:
- 16. The payment flow makes no ai-service call.
- 17. LLM output is a suggestion. Only deterministic policy acts on it, and never with a financial action.
- 18. A model id or version is recorded only as the provider reported it. Without a model there is no output: the reply is
  "model unavailable", never a placeholder.

## Options
1. Keep the agents but make them advisory. This leaves the theatre and the hot-path latency.
2. Make classification deterministic, delete the agent theatre, and keep one async LLM task with schema validation and a
   deterministic policy. **Chosen.**
3. Remove AI entirely. Honest, but drops the one useful task (understanding customer replies).

## Decision
Option 2. Detailed in ADR-003.
- Failure taxonomy: a pure Java table keyed by Razorpay `error_reason`; only when no reason is sent, `error_source`
  `gateway`/`razorpay`. Every mapping cites the Razorpay docs URL; anything unmapped is `UNCLASSIFIED`. The table
  version goes into a new `classification_results.taxonomy_version` column; `model_version` stays NULL for
  deterministic rows (it is a model field).
- Compliance blocks `UNCLASSIFIED` and every non-taxonomy (legacy) class, replacing both the `UNKNOWN` block and the
  LOW_CONFIDENCE block: an evidence-less failure is still blocked, so compliance is not weakened (invariant 7).
- Strategy and eligibility are keyed by taxonomy class. All confidence gates are removed.
- The customer-reply task is async (DB job). Its output is a validated suggestion; a deterministic policy maps intents
  to effects (OPT_OUT stops contact, ALREADY_PAID triggers a reconciler lookup, DISPUTE opens a review, the rest are shown
  only).

## Architecture
- Backend: domain classifier `com.mend.taxonomy` (pure Java, no Spring/HTTP/JPA: `FailureTaxonomy`, `FailureClass`,
  `ProviderFailure`).
- `ClassificationService` calls the classifier (no client). The orchestrator runs strategy → compliance → intent with no
  agent step.
- Reply parsing:
  - `POST /api/v1/campaigns/{id}/replies` (user call, `TenantGuard`) stores a `customer_replies` row with status `PENDING`.
  - A scheduled `ReplyParsingJob` claims PENDING rows (`SKIP LOCKED`) and calls ai-service `POST /api/v1/parse_reply`.
  - The job validates the result against the schema and stores a `reply_suggestions` row.
  - `ReplyPolicy` applies the deterministic effects.
- ai-service: FastAPI with `/health` and `/api/v1/parse_reply` only. No API key means `503 model_unavailable`. The model
  id is copied from the provider response.
- Frontend: the "AI Agent Decisions" tab and `AgentDecisionStack` go away. The campaign page shows the failure class with
  its source (the Razorpay reason), and reply suggestions labelled as suggestions.

## Failure modes
| failure | behaviour |
|---|---|
| ai-service down / slow | payment flow unaffected (no call); reply rows stay PENDING, retried with backoff, `attempts` capped, then `FAILED` (visible) |
| no API key | ai-service answers `503 model_unavailable`; reply stays PENDING/FAILED with that code; no suggestion |
| invalid LLM output (schema) | suggestion stored as `INVALID` with the validation error; never coerced; no effect |
| LLM says ALREADY_PAID | read-only check: the campaign's AWAITING_PAYMENT link intents get `reconcile_after = now` (brings forward the lookup the reconciler runs anyway: the reply changes when the check runs, never what it decides; that branch itself never resends, and any later step is the reconciler's own evidence-based decision on its own schedule); NEEDS_RECONCILIATION intents are not touched (SAFE_RETRY stays reconciler-owned); no link intent → review `CUSTOMER_SAYS_PAID`; never marks recovered (invariant 2) |
| LLM says OPT_OUT | `campaigns.contact_opted_out_at` set + audit; compliance blocks `CUSTOMER_OPTED_OUT` for every new intent and at the pre-dispatch re-check (pending intents end through the existing fenced abandon path; EXECUTING/AWAITING intents are not touched); a merchant admin clears it via `DELETE /api/v1/campaigns/{id}/opt-out` (audited) |
| suggestion invalid or abstained | stored, shown, no effect |
| legacy class (pre-M3 `BANK_DECLINED` etc., AI confidence) | treated as UNCLASSIFIED (fail closed) at eligibility and compliance; rows are not rewritten |
| unknown Razorpay reason | `UNCLASSIFIED` → ineligible (EXHAUSTED), as `UNKNOWN` today; fail closed |

## Security implications
- Replies are PII. They are stored tenant-scoped (merchant_id), sent only to ai-service, and never logged. Retention: 90
  days (purge job is M5-owned and recorded as such); the text is minimised (length cap 2000).
- LLM output is untrusted (invariant 8): it is schema-validated and enum-restricted, and dates are parsed strictly.
- The internal token for ai-service is kept; no key is written to any file.

## Testing strategy
- Taxonomy: one test per documented reason (table-driven), the fixture `payment_failed_entity.json`, and the source fallback.
- Hot path: the payment-flow integration tests run with no `AiClassificationClient` bean / an unreachable ai-service URL,
  and assert identical results.
- Theatre removal: each test that asserted theatre is rewritten to assert the deterministic behaviour; the count must not
  drop.
- Reply task: API (tenant guard), job (fake ai-service: valid, invalid, 503, timeout), policy per intent, PII scope. Each
  bug fix gets a revert proof.
- ai-service: pytest for schema validation, `model_unavailable` and the health endpoint.
- Eval: script determinism (same dataset version → same baseline metrics).

## Metrics
Eval: per-class precision/recall/F1, macro F1, confusion matrix, invalid-output rate, abstention rate. Runtime metrics
registration stays M5.

## Tradeoffs
- An undocumented Razorpay reason becomes `UNCLASSIFIED` (no automatic recovery) until it is mapped with evidence.
- Deleting the "six agents" removes demo appeal; replaced by an honest description.
- The reply task needs a human or channel to deliver replies (no inbound channel exists); M3 provides the API only.

## Exit criteria
§50 checklist in `docs/m3/M3-plan.md`: all ticked with commits; backend/frontend/ai-service suites green; ADR-003 Accepted.
