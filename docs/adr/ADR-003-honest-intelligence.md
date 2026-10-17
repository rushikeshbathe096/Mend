# ADR-003 — Honest intelligence: deterministic payment path, one async AI task

Status: **Proposed** (M3 U2.0, 2026-10-18). Accepted at U2.7 with evidence.
Milestone: M3 · Depends on: ADR-001, ADR-002

## Context
Two synchronous ai-service calls (classification, "six-agent" orchestration) sit on the payment hot path. Their
confidences and model versions are constants or LLM self-reports. They gate eligibility, strategy and compliance, and
they can stop or hold a recovery. Inventory: `docs/m3/M3-analysis.md`.

## Decision
1. **Deterministic failure taxonomy.** A pure Java table maps the Razorpay payment `error_reason`, then `error_source`,
   to a class:

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
   | `UNCLASSIFIED` | everything undocumented |

   Every row cites https://razorpay.com/docs/errors/payments/list/. No confidence is produced or stored for it. The
   recorded version is the table version (`razorpay-error-taxonomy-v1`), not a model.
2. **AI off the payment hot path.** webhook → taxonomy → strategy → compliance → intent makes no ai-service call. The
   3 s timeouts no longer apply to payments.
3. **No theatre.** Delete all of these:
   - the agent decision engine and the LangGraph agents
   - the orchestration and agent-decision endpoints
   - hardcoded confidences, invented model versions, synthetic fallbacks and risk scores
   - every confidence-based gate

   Historical `agent_decision_records` and `confidence` values are kept as legacy columns and rows, never rewritten.
   Columns that were NOT NULL become nullable through new migrations.
4. **Strategy and eligibility by class.** Each class maps to the strategy its pre-M3 equivalent had:

   | class | pre-M3 equivalent |
   |---|---|
   | `INSUFFICIENT_FUNDS` | `INSUFFICIENT_FUNDS` |
   | `LIMIT_EXCEEDED` | `LIMIT_EXCEEDED` |
   | `BANK_TECHNICAL`, `GATEWAY_ERROR` | `NETWORK_FAILURE` |
   | `AUTHENTICATION_FAILURE` | `AUTHENTICATION_FAILED` |
   | `INSTRUMENT_INVALID` | `CARD_EXPIRED` |
   | `CUSTOMER_DECLINED`, `MANDATE_INVALID` | `CUSTOMER_ACTION_REQUIRED` |
   | `UNCLASSIFIED` | ineligible, like `UNKNOWN` |

   Low-confidence MANUAL_REVIEW gating disappears. MANUAL_REVIEW now comes only from the strategy table (merchant's
   enabled actions) and from explicit review codes.
5. **One AI task: customer-reply parsing, asynchronous.** The output is a schema-validated *suggestion* (intent enum,
   optional promised date, abstain flag). A deterministic policy owns every effect:

   | intent | effect |
   |---|---|
   | OPT_OUT | stop contact (compliance) |
   | ALREADY_PAID | reconciler lookup only, never "recovered" |
   | DISPUTE | review item |
   | anything else | shown only |

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
