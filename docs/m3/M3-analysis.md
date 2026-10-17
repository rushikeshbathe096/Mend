# M3 analysis — inventory of the AI path (branch `m3/honest-intelligence` @ `1c7fb7d`)

Paths are relative to the repository root; `J/` = `backend/src/main/java/com/mend/`. Line numbers are as of `1c7fb7d`.

## 1. Payment hot path today (webhook → intent)

```
WebhookController → WebhookService (outbox) → Redis stream → RedisWebhookEventConsumer
  → DefaultEventProcessingService.processEvent                          J/service/DefaultEventProcessingService.java:87-99
    → ClassificationService.classifyAndPersist                           J/service/ClassificationService.java:45
        → AiClassificationClient.classify  (HTTP POST ai-service /api/v1/classify, connect 2 s / read 3 s)
                                                                          J/service/ClassificationService.java:68
                                                                          J/client/FastApiClassificationClient.java:33-34,53-54
        catch → fabricated fallback UNKNOWN / 0.30 / REVIEW_REQUIRED / "v1.0.0-fallback"  ClassificationService.java:69-77
    → CampaignLifecycleService.processClassificationResult               J/service/CampaignLifecycleService.java:210
        → evaluateEligibility: UNKNOWN or confidence < 0.50 → EXHAUSTED  CampaignLifecycleService.java (evaluateEligibility, "0.50")
        → RecoveryOrchestratorService.orchestrateRecovery                J/service/RecoveryOrchestratorService.java:79
            → AgentDecisionEngine.decide                                 RecoveryOrchestratorService.java:117
                → AiClassificationClient.orchestrateAgent (HTTP POST ai-service /api/v1/agent/orchestrate)
                                                                          J/agent/DefaultAgentDecisionEngine.java:84
                → AI may return STOP_RECOVERY / requiresHumanApproval     DefaultAgentDecisionEngine.java:90-106
            → agent STOP → recovery stops (AI decides a financial action) RecoveryOrchestratorService.java:149-154
            → agent requiresHumanApproval → review, no intent             RecoveryOrchestratorService.java:187-204
            → RecoveryStrategyService → RecoveryStrategyEngine (confidence < 0.80 → MANUAL_REVIEW)
                                                                          J/strategy/RecoveryStrategyEngine.java:93-105
            → ComplianceService → ComplianceEngine (confidence < 0.80 → LOW_CONFIDENCE block)
                                                                          J/compliance/ComplianceEngine.java:127-146
            → ActionIntentService.createActionIntentFromCompliance (intent)
```

**On the hot path:** two synchronous ai-service HTTP calls per new failure (classify, orchestrate), both inside the event
transaction; a 3 s read timeout each; AI confidence gates eligibility, strategy and compliance; the agent can stop a
recovery or hold it for review. All of this violates the M3 rules (AI never on the payment hot path; AI never decides a
financial action).

## 2. Backend AI call sites and theatre

| item | location | what it does |
|---|---|---|
| `AiClassificationClient` (interface) | `J/client/AiClassificationClient.java:10-11` | `classify`, `orchestrateAgent` |
| `FastApiClassificationClient` | `J/client/FastApiClassificationClient.java:33-34` (timeouts 2000/3000 ms), `:53` classify, orchestrate below | HTTP client to ai-service |
| `ClassificationService.classifyAndPersist` | `J/service/ClassificationService.java:68` | hot-path AI call |
| fabricated fallback | `ClassificationService.java:72-76` | `UNKNOWN`, confidence **0.30**, `REVIEW_REQUIRED`, model **"v1.0.0-fallback"** |
| eligibility confidence gate | `J/service/CampaignLifecycleService.java` `evaluateEligibility` (`0.50`), ineligibility text (`0.50`) | confidence < 0.50 → EXHAUSTED |
| `DefaultAgentDecisionEngine` | `J/agent/DefaultAgentDecisionEngine.java:41,58` confidence **1.00**; `:46,:63` model **"v2.0-deterministic"**; `:84` AI call; `:91` threshold **0.70**; `:98` default confidence **0.85**; `:103` model **"v2.0-langgraph"**; `:128` **0.90**, `:143` **0.88**, `:159` **0.80**, `:133/148/164` model **"v2.0-fallback-rule"** | "six-agent" decision; fabricated confidences and versions |
| `AgentDecision`, `AgentContext`, `AgentDecisionEngine` | `J/agent/*.java` | decision record types |
| `RecoveryOrchestratorService` | `J/service/RecoveryOrchestratorService.java:117` decide, `:120-137` persist `AgentDecisionRecord`, `:149-154` AI stop, `:187-204` AI review gate | orchestration |
| `AgentDecisionRecord` + repository + DTO | `J/domain/entity/AgentDecisionRecord.java`, `J/domain/repository/AgentDecisionRecordRepository.java`, `J/dto/AgentDecisionRecordDto.java`; table V9 | stores fabricated decisions |
| `AgentOrchestrationController` | `J/controller/AgentOrchestrationController.java:32-55` `POST /api/v1/agent/orchestrate` | proxies ai-service agent |
| `AgentDecisionQueryController` | `J/controller/AgentDecisionQueryController.java:25,45` `GET /campaigns/{id}/decisions`, `GET /agent/timeline` | serves agent records |
| strategy confidence gate | `J/strategy/RecoveryStrategyEngine.java:28` (`mend.recovery.strategy.min-confidence:0.80`), `:93-105` | low confidence → MANUAL_REVIEW |
| compliance confidence gate | `J/compliance/ComplianceEngine.java:29` (`mend.compliance.min-confidence:0.80`), `:127-146` | UNKNOWN → block; low confidence → LOW_CONFIDENCE block |
| properties | `backend/src/main/resources/application.properties:27-30` (ai base url, timeouts, token), `:99-101` (min-confidence 0.80 ×2) | config |
| AI-shaped DTOs | `J/dto/ai/{ClassificationRequestDto,ClassificationResponseDto,AgentOrchestrationRequestDto,AgentOrchestrationResponseDto}.java` | wire types |
| `RecommendedAction` | `J/domain/enums/RecommendedAction.java` | AI "recommendation" stored per classification |
| `FailureClass` | `J/domain/enums/FailureClass.java` (`INSUFFICIENT_FUNDS, BANK_DECLINED, CARD_EXPIRED, AUTHENTICATION_FAILED, NETWORK_FAILURE, LIMIT_EXCEEDED, UNKNOWN`) | AI output vocabulary, no provider mapping |
| review/payment DTO agent fields | `J/dto/ReviewItemDto.java:24,39-41,67,73-80` (`confidence`, `agentDecision`, `agentConfidence`, `agentModelVersion` …); `J/dto/PaymentDetailDto.java:10`; `J/dto/StartRecoveryResponseDto.java:11`; `J/controller/PaymentController.java:31,112-128`; `J/service/HumanApprovalService.java:74,85` | surfaces theatre to the UI |
| demo | `J/controller/DemoController.java:221-227` (classification "0.95", recorded agent decision, "six-agent consensus … confidence 95%"), `:200` AGENT_FAILURE scenario | labelled SIMULATED, dev profile only |
| dev seed | `J/config/DevDataInitializer.java:275` random confidence 0.70–0.98, `:304,315` model "v1.2", `:356-359…` fabricated agent decision records | dev profile only |

Schema: `classification_results.confidence NUMERIC(3,2) NOT NULL` (V1:142), `model_version VARCHAR(50)` (V1:146);
`campaigns.confidence` (V1:75); `recovery_decisions.confidence` (V6:11); `agent_decision_records` (V9).

## 3. ai-service (Python)

| item | location | what it does |
|---|---|---|
| `POST /api/v1/classify` | `ai-service/main.py:292` | heuristic or LLM classification |
| heuristic confidences / versions | `main.py:73` default **"v1.0.0-bounded-heuristic"**; `:103` **0.10** + `:106` **"v1.0.0-bounded-safety-guard"**; `:120,135` **0.95**; `:150,164,193` **0.90**; `:178` **0.85**; `:206` **0.30** | fabricated confidence per keyword |
| LLM classification | `main.py:217-268` (`query_llm_agent`; asks the LLM to invent a confidence, `:227,246`) | LLM self-reported confidence |
| `POST /api/v1/agent/orchestrate` | `ai-service/agent/router.py:32`; `:111` default confidence **0.90**; `:122` model **"v1.7.0-multi-agent"** | LangGraph "six agents" |
| LangGraph graph | `ai-service/agent/graph.py:34-660` (14 nodes: supervisor, risk & fraud, decision, strategy, engagement, consensus, compliance, escalate, human approval, create/execute intent, observe, outcome, re-evaluate); `:24` threshold **0.80**; `:77` **0.99**; `:100,154,243,300,347` models **"v1.7.0-*"**; `:119` risk score **0.12**; `:149,184-209,294,343,684-712` confidences | agent theatre |
| synthetic MCP tools | `ai-service/agent/mcp_tools.py:56` riskScore **0.15**, `:157-159` fabricated success rates, `:177` score **0.12**, `:217` recovery rate **0.42**; `agent/tools.py:41,88-116` | invented data fed to "agents" |
| state models | `agent/state.py:44,119,159` model versions | |
| checkpointer | `agent/checkpointer.py` (LangGraph Postgres checkpointer, `POSTGRES_DB_URI`) | persistence for the graph |
| LLM factory | `agent/llm.py:8-35` (`GEMINI_API_KEY` / `OPENAI_API_KEY`) | provider selection |
| benchmarks | `performance_benchmark_results.json`, `recovery_effectiveness_benchmark.json` (rewritten by tests, see Phase 1 log) | |
| tests | `test_main.py`, `test_agent.py`, `test_phase18_reliability.py`, `test_phase18_5_persistence.py`, `test_phase19_security.py` (51 tests) | |
| deps | `requirements.txt`: langgraph, langgraph-checkpoint-postgres, psycopg(+pool), langchain-core/-google-genai/-openai, python-dotenv, PyYAML (never imported, audit §3) | |

## 4. Frontend

| item | location |
|---|---|
| `AgentDecisionStack` | `frontend/src/components/common/AgentDecisionStack.tsx` (+ test); used in `frontend/src/app/campaigns/[id]/page.tsx:12,29,308,398-403` ("AI Agent Decisions" tab) |
| agent decisions on payment detail | `frontend/src/app/payments/[paymentId]/page.tsx:163-187` |
| agent decisions after start-recovery | `frontend/src/components/recovery/StartRecoveryModal.tsx:449-451` |
| agent confidence on reviews | `frontend/src/app/actions/page.tsx:292-293,462-463` |
| types | `frontend/src/types/index.ts` (`AgentDecisionRecordDto`, `confidence`, `modelVersion`) |
| landing page "Six Agents" copy | `frontend/src/app/page.tsx` (agents table), `README.md` "## Six Agents" |

## 5. Backend tests on the AI path (test methods, `@Test`/`@ParameterizedTest` count)

`AiRecoveryAgentIntegrationTest` 11, `ComplianceEngineTest` 11, `RecoveryStrategyEngineTest` 10, `Phase19_5…` 10,
`ComplianceServiceTest` 7, `CampaignLifecycleServiceTest` 6, `RecoveryOrchestrationIntegrationTest` 4,
`Phase18Reliability…` 4, `ClassificationServiceTest` 3, `AiAgentIntegrationTest` 3, `AiClassificationClientTest` 3,
`CrossTenantReadIsolationIntegrationTest` 3 (agent endpoints among them), plus consumer/E2E tests that mock
`AiClassificationClient`. Each test that asserts theatre is rewritten in U2.3 to assert deterministic behaviour; the total
count may not drop.

## 6. Razorpay error vocabulary (for U2.1)

The payment entity carries `error_code`, `error_description`, `error_source`, `error_step`, `error_reason`
(doc-derived fixture `backend/src/test/resources/razorpay/doc-derived/payment_failed_entity.json`: `BAD_REQUEST_ERROR` /
`customer` / `payment_authentication` / `payment_cancelled`). Razorpay documents `reason` as "the exact error reason. It
can be handled programmatically" (https://razorpay.com/docs/errors/) and lists every payment reason with a description at
https://razorpay.com/docs/errors/payments/list/ (fetched 2026-10-18), plus the `source` values `customer`, `business`,
`gateway` ("usually transient"), `razorpay` ("retry after a short delay"). `error_code` values are not enumerated on either
page, so the classifier does not key on them. Today the backend reads `error_reason` (else `error_code`) into a single
`failureCode` string (`J/service/DefaultEventProcessingService.java:115-134`) and sends it to the AI.
