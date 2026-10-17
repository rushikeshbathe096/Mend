# ADR-003 — Honest intelligence: deterministic payment path, one async AI task

Status: **Proposed** (M3 U2.0, 2026-10-18). Accepted at U2.7 with evidence.
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
   | `UNCLASSIFIED` | pre-M3 `UNKNOWN` | ineligible → EXHAUSTED; compliance block |

   Pre-M3 `BANK_DECLINED` has no taxonomy equivalent: the documented declines (`card_declined`, `payment_declined`,
   `debit_declined`) are deliberately unmapped.

   Legacy rows (pre-M3 classes, AI confidence) are treated as UNCLASSIFIED (fail closed) wherever they are re-evaluated.
   They are never rewritten.

   All confidence gates go: eligibility 0.50, strategy and compliance 0.80, and the agent's 0.70. Compliance's
   `UNKNOWN` and `LOW_CONFIDENCE` blocks are replaced by a block on `UNCLASSIFIED` or any non-taxonomy class, so a failure
   without evidence is still blocked. MANUAL_REVIEW now comes only from the strategy table (merchant's enabled actions)
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
6. **Evaluation**: a versioned SYNTHETIC-labelled dataset and a keyword baseline. Model metrics are recorded only when a
   model was actually run.

## Why
Payment recovery must be explainable from provider evidence (ADR-001). A documented error reason is evidence; a made-up
confidence is not. Async parsing keeps AI useful without putting it between a failure and money.

## Consequences
- ai-service outages no longer change payment outcomes.
- An undocumented Razorpay reason is not auto-recovered until it is mapped with evidence.
- The UI loses the "AI Agent Decisions" tab; review items lose agent fields (historical rows remain in the database).

## Revisit conditions
Razorpay documents new reasons; real recordings contradict a mapping; an inbound reply channel is added (M4+); a second
AI task is proposed (needs its own ADR).

## Evidence
To be filled at U2.7 (commits, test counts, eval results).
