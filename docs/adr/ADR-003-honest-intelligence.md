# ADR-003 — Honest intelligence: deterministic payment path, one async AI task

Status: **Accepted** (M3 U2.7, 2026-10-18). Proposed at U2.0; evidence below.
Milestone: M3 · Depends on: ADR-001, ADR-002

## Context
Two synchronous ai-service calls (classification, "six-agent" orchestration) sit on the payment hot path. Their
confidences and model versions are constants or LLM self-reports. They gate eligibility, strategy and compliance, and
they can stop or hold a recovery. Inventory: `docs/m3/M3-analysis.md`.

## Decision
1. **Deterministic failure taxonomy.** A pure Java table maps the Razorpay payment `error_reason` to a class:

   | class | gets |
   |---|---|
   | `INSUFFICIENT_FUNDS` | — |
   | `LIMIT_EXCEEDED` | added with evidence: the documented limit family |
   | `BANK_TECHNICAL` | — |
   | `GATEWAY_ERROR` | — |
   | `AUTHENTICATION_FAILURE` | — |
   | `CUSTOMER_DECLINED` | — |
   | `INSTRUMENT_INVALID` | added with evidence: card expired / instrument blocked or inactive |
   | `MANDATE_INVALID` | — |
   | `UNCLASSIFIED` | everything unmapped |

   Only when Razorpay sends no reason does `error_source` `gateway` / `razorpay` decide `GATEWAY_ERROR`. A reason that is
   present but unmapped stays `UNCLASSIFIED`. Every row cites https://razorpay.com/docs/errors/payments/list/; all
   rows share one URL because every mapped reason is listed on that one page. Reasons the docs do not attribute to an
   actor stay unmapped: `card_declined`, `payment_declined`, `debit_declined`, `payment_failed`,
   `authorisation_declined_by_psp`, `payment_session_expired` and `payment_collect_request_expired`.
   No confidence is produced. The table version `razorpay-error-taxonomy-v1` is stored in its own
   `classification_results.taxonomy_version` column; it is never written into `model_version`.
2. **AI off the payment hot path.** webhook → taxonomy → strategy → compliance → intent makes no ai-service call. The
   3 s timeouts no longer apply to payments.
3. **No theatre.** Delete all of these:
   - the agent decision engine and the LangGraph agents
   - the orchestration and agent-decision endpoints
   - hardcoded confidences, invented model versions, synthetic fallbacks and risk scores
   - every confidence-based gate

   Historical `agent_decision_records` and `confidence` values are kept as legacy columns and rows, never rewritten.
   Columns that were NOT NULL become nullable through new migrations.
4. **Strategy and eligibility by class.** Each class takes the strategy rule of its pre-M3 equivalent
   (`RecoveryStrategyEngine`):

   | class | rule | strategy |
   |---|---|---|
   | `INSUFFICIENT_FUNDS` | pre-M3 `INSUFFICIENT_FUNDS` | RETRY_LATER, RETRY_LATER, then CUSTOMER_ACTION_REQUIRED |
   | `BANK_TECHNICAL`, `GATEWAY_ERROR` | pre-M3 `NETWORK_FAILURE` | RETRY_IMMEDIATELY, then RETRY_LATER |
   | `LIMIT_EXCEEDED` | pre-M3 `LIMIT_EXCEEDED` | CUSTOMER_ACTION_REQUIRED |
   | `AUTHENTICATION_FAILURE` | pre-M3 `AUTHENTICATION_FAILED` | CUSTOMER_ACTION_REQUIRED |
   | `INSTRUMENT_INVALID` | pre-M3 `CARD_EXPIRED` | CUSTOMER_ACTION_REQUIRED |
   | `CUSTOMER_DECLINED`, `MANDATE_INVALID` | new rows | CUSTOMER_ACTION_REQUIRED (a payment link) |
   | `UNCLASSIFIED` | owner decision B (2026-10-18; was: pre-M3 `UNKNOWN`, ineligible) | CUSTOMER_ACTION_REQUIRED (a payment link) only; a retry is blocked by compliance |

   Pre-M3 `BANK_DECLINED` has no taxonomy equivalent: the documented declines (`card_declined`, `payment_declined`,
   `debit_declined`) are deliberately unmapped.

   Legacy rows (pre-M3 classes, AI confidence, or no taxonomy version) fail closed wherever they are re-evaluated:
   ineligible, strategy MANUAL_REVIEW, compliance `UNKNOWN_CLASSIFICATION`. They are never rewritten.

   **Amendment, owner decision B (2026-10-18).** An unmapped decline is still never guessed. But a *deterministic*
   UNCLASSIFIED row may get a payment link: the customer chooses whether to pay, and MEND never moves money on a guess.
   This covers an undocumented reason, or a documented one with no actor (`card_declined`, `payment_declined`,
   `debit_declined`, `payment_failed`, `authorisation_declined_by_psp`, `payment_session_expired`,
   `payment_collect_request_expired`).
   - The row is eligible.
   - The strategy table gives CUSTOMER_ACTION_REQUIRED (strategy policy v2.1).
   - Compliance rule 3c blocks RETRY_IMMEDIATELY / RETRY_LATER for UNCLASSIFIED with `RETRY_NOT_ALLOWED_UNCLASSIFIED`
     (compliance policy v2.2). Neither the table nor compliance alone can allow a retry or a debit for it.

   All confidence gates go: eligibility 0.50, strategy and compliance 0.80, and the agent's 0.70. Compliance's
   `UNKNOWN` and `LOW_CONFIDENCE` blocks are replaced by a block on any non-taxonomy class (and, before decision B, on
   `UNCLASSIFIED`), so a failure without evidence is still never retried. MANUAL_REVIEW now comes only from the strategy table (merchant's enabled actions)
   and from explicit review codes.
5. **One AI task: customer-reply parsing, asynchronous.** The output is a schema-validated *suggestion* (intent enum,
   optional promised date, abstain flag). A deterministic policy owns every effect:

   | intent | effect |
   |---|---|
   | OPT_OUT | fail-closed compliance block `CUSTOMER_OPTED_OUT`, checked for new intents and at the pre-dispatch re-check; audited; a merchant admin can clear it |
   | ALREADY_PAID | the campaign's AWAITING_PAYMENT link intents get an immediate evidence lookup (the reply only brings forward the lookup the reconciler would run anyway; that branch never resends; any later step is the reconciler's own evidence-based decision on its own schedule); NEEDS_RECONCILIATION intents are not touched, so SAFE_RETRY is never AI-triggered; no link intent → review; never "recovered" |
   | DISPUTE | review item, no duplicate of an open review |
   | anything else, an abstained suggestion, or an invalid one | stored and shown only |

   LLM output never triggers a financial action. The model id comes from the provider response. With no key, the reply
   is `model_unavailable` and there is no output.

   Implementation (U2.4):
   - Intents: `OPT_OUT`, `ALREADY_PAID`, `DISPUTE`, `PROMISE_TO_PAY` (with an optional promised date) and `OTHER`.
   - Strict schema: exactly `intent`, `promised_date` and `abstain`; a date only with a promise. Invalid output is
     stored as invalid and never coerced.
   - Model: Gemini `generateContent` over httpx. `model_id` is the response's `modelVersion`, NULL when it is absent.
   - The opt-out lives in `campaigns.contact_opted_out_at`, which is read-only in JPA. The executor reads it `FOR SHARE`
     in the dispatch transaction (compliance policy v2.1).
   - The PROMISE_TO_PAY date is shown only: no reschedule.
   - Replies are PII: text ≤ 2000 characters, never logged or audited, retention 90 days (purge job M5-owned).
6. **Evaluation**: a versioned SYNTHETIC-labelled dataset and a keyword baseline. Model metrics are recorded only when a
   model was actually run.

## Why
Payment recovery must be explainable from provider evidence (ADR-001). A documented error reason is evidence; a made-up
confidence is not. Async parsing keeps AI useful without putting it between a failure and money.

## Consequences
- ai-service outages no longer change payment outcomes.
- An undocumented Razorpay reason is never retried until it is mapped with evidence. Under decision B it gets a payment
  link, so the customer can still pay.
- A classification row decides only if it carries a taxonomy version. A pre-M3 row fails closed even when its class name
  matches a taxonomy class (`INSUFFICIENT_FUNDS`, `LIMIT_EXCEEDED`), and so does a campaign with no classification row.
- Compliance checks the class before the strategy rules, so a legacy campaign is blocked (`UNKNOWN_CLASSIFICATION`) and is
  never routed to a review it could not pass.
- Intents created before M3 are not re-classified. The pre-dispatch check re-reads the stored compliance decision
  (invariant 7). *Closed by the M3 follow-up (2026-10-18):* a decision not made under policy v2.0 or later (v1.x, or an
  unknown version) cannot authorize a dispatch. The executor fails closed with `LEGACY_DECISION`. The campaign returns to
  ELIGIBLE with a `LEGACY_DECISION` review, and approving it re-evaluates compliance under the current policy. No
  historical row is rewritten.
- Under owner decision B (2026-10-18, §4), an UNCLASSIFIED failure is recovered only by a payment link. A retry or debit
  for it is impossible by two independent rules: the strategy table and compliance rule 3c.
- All database sessions run in UTC (Hikari `connection-init-sql`, Flyway `init-sqls`, Hibernate `jdbc.time_zone`).
  TIMESTAMP-without-time-zone defaults therefore agree with the UTC Instants Java writes. Before this, a local JVM zone
  (seen: Asia/Kolkata) put DB-defaulted values 5 h 30 min ahead. Rows defaulted that way earlier are not rewritten.
  The 21 TIMESTAMP-without-time-zone columns with a DB default, all now consistent with what Java writes:
  - V1 `created_at` on roles, users, merchants, merchant_users, campaigns, campaign_attempts, action_intents,
    classification_results, review_queue, merchant_config and audit_logs;
  - V1 `updated_at` on users, merchants, campaigns and merchant_config;
  - V1 `webhook_events.received_at`;
  - V9 `agent_decision_records.created_at`;
  - V20 `customer_replies` `next_attempt_at`, `created_at` and `updated_at`, and `reply_suggestions.created_at`.

  The TIMESTAMPTZ columns (V6–V8, V11, V12) were already zone-independent.
- Reply-raised reviews (`CUSTOMER_DISPUTE`, `CUSTOMER_SAYS_PAID`) use the existing approval queue. Approving one resumes
  collection, and the review text says so. A separate review kind for "resolve, do not collect" is M4-owned.
- The UI loses the "AI Agent Decisions" tab; review items lose agent fields (historical rows remain in the database).

## Revisit conditions
Razorpay documents new reasons; real recordings contradict a mapping; an inbound reply channel is added (M4+); a second
AI task is proposed (needs its own ADR).

## Evidence
| decision | commits | proof |
|---|---|---|
| 1. Taxonomy | `314bf9e`, `0014eed` | `FailureTaxonomyTest`, `TaxonomyPurityTest` |
| 2. Off the hot path | `31a1949`, `ecf1d83`, `1c2363f` | `AiOffHotPathTest`: unreachable ai-service, flow to RECOVERED, no AI bean in the context |
| 3. No theatre | `1c2363f`, `f005c62`, `3aedd1d`, `53ec57d` | `AiTheatreRemovedIntegrationTest`, `HonestApiDtoTest` |
| 4. Strategy and eligibility by class | `31a1949`, `ecf1d83` | `ComplianceEngineTest`, `RecoveryStrategyEngineTest` (legacy rows fail closed) |
| 5. One AI task | `8fe7e0c`, `3f6209d`, `b0645f8`, `3ff511f`, `be25b4b` | `ReplyParsingIntegrationTest` (15, real PostgreSQL and threads; incl. log capture of the reply text), `ReplySuggestionValidatorTest`, ai-service `test_parse_reply.py` / `test_main.py` |
| 6. Evaluation | `2bc479d`, `e2c72c1` | `docs/m3/eval/RESULTS.md`; model **TBD** (no key) |

Final run: backend 941/0/0/0, frontend 62 (lint 0 errors), ai-service 90, migrations V1–V21 validate OK
(`docs/m3/m3-final-test-run-summary.txt`). Each unit had a fresh-subagent review, findings fixed with revert proofs
(`docs/next-phase/progress.md`).
