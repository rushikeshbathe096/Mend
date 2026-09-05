import uuid
import logging
import os
from fastapi import APIRouter, Header, HTTPException, status
from agent.state import (
    AgentOrchestrationRequest,
    AgentOrchestrationResponse,
    ActionDecisionEnum,
    RiskLevelEnum,
    AgentNextStepEnum
)
from agent.graph import build_recovery_agent_graph

logger = logging.getLogger("mend-ai-service")
router = APIRouter(prefix="/api/v1/agent", tags=["AI Recovery Agent"])

_agent_graph = None

def require_internal_auth(internal_token: str | None) -> None:
    configured_token = os.getenv("MEND_AI_INTERNAL_TOKEN")
    if configured_token and internal_token != configured_token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid internal service token")

def get_agent_graph(backend_url: str):
    global _agent_graph
    if _agent_graph is None:
        _agent_graph = build_recovery_agent_graph(backend_url=backend_url)
    return _agent_graph

@router.post("/orchestrate", response_model=AgentOrchestrationResponse, status_code=status.HTTP_200_OK)
def orchestrate_agent_flow(
    req: AgentOrchestrationRequest,
    x_mend_internal_token: str | None = Header(default=None),
):
    require_internal_auth(x_mend_internal_token)
    if not req.merchantId or not req.merchantId.strip():
        raise HTTPException(status_code=400, detail="merchantId must not be empty")
    if not req.campaignId or not req.campaignId.strip():
        raise HTTPException(status_code=400, detail="campaignId must not be empty")

    try:
        configured_backend_url = os.getenv("MEND_BACKEND_URL", "http://localhost:8080")
        # backendUrl is retained in the request model for compatibility, but is never
        # trusted: accepting caller-controlled URLs would turn MCP reads into SSRF.
        backend_url = configured_backend_url
        app = build_recovery_agent_graph(backend_url=backend_url)

        trace_id = req.traceId or req.correlationId or f"trace_{uuid.uuid4().hex[:12]}"
        correlation_id = req.correlationId or trace_id

        initial_state = {
            "merchant_id": req.merchantId,
            "campaign_id": req.campaignId,
            "payment_id": req.paymentId,
            "attempt_number": req.attemptCount or 1,
            "trace_id": trace_id,
            "correlation_id": correlation_id,
            "agent_trace_id": trace_id,
            "payment_context": {
                "eventId": req.eventId,
                "failureCode": req.failureCode,
                "failureReason": req.failureReason,
                "amountInCents": req.amountInCents or 0
            }
        }

        # Config with thread_id for LangGraph checkpointer persistence
        config = {
            "configurable": {
                "thread_id": f"campaign_{req.campaignId}"
            }
        }

        final_state = app.invoke(initial_state, config=config)

        decision_str = final_state.get("proposed_decision") or final_state.get("proposed_action") or "RETRY_PAYMENT"
        selected_act_str = final_state.get("selected_action") or decision_str
        risk_str = final_state.get("risk_level", "LOW")
        next_step_str = final_state.get("next_step", "EXECUTE")

        # Map enums safely
        try:
            decision_enum = ActionDecisionEnum(decision_str)
        except ValueError:
            decision_enum = ActionDecisionEnum.RETRY_PAYMENT

        try:
            risk_enum = RiskLevelEnum(risk_str)
        except ValueError:
            risk_enum = RiskLevelEnum.LOW

        try:
            next_step_enum = AgentNextStepEnum(next_step_str)
        except ValueError:
            next_step_enum = AgentNextStepEnum.EXECUTE

        comp_status = final_state.get("compliance_result", {}).get("status", "COMPLIANCE_ALLOWED")

        return AgentOrchestrationResponse(
            agentTraceId=final_state.get("agent_trace_id", trace_id),
            traceId=trace_id,
            correlationId=correlation_id,
            merchantId=final_state.get("merchant_id", req.merchantId),
            campaignId=final_state.get("campaign_id", req.campaignId),
            paymentId=final_state.get("payment_id", req.paymentId),
            decision=decision_enum,
            selectedAction=selected_act_str,
            confidence=final_state.get("confidence", 0.90),
            riskLevel=risk_enum,
            reasoningSummary=final_state.get("reasoning_summary", "Multi-agent recovery orchestration completed."),
            evidence=final_state.get("evidence", []),
            requiresHumanApproval=final_state.get("human_review_required", final_state.get("human_approval_required", False)),
            complianceStatus=comp_status,
            nextStep=next_step_enum,
            executionResult=final_state.get("execution_result"),
            iterationCount=final_state.get("iteration", 1),
            fallbackUsed=final_state.get("fallback_used", True),
            stopReason=final_state.get("stop_reason"),
            modelVersion=final_state.get("model_version", "v1.7.0-multi-agent"),
            agentVersion=final_state.get("agent_version", "v1.7.0"),
            strategyRecommendation=final_state.get("strategy_recommendation"),
            customerEngagement=final_state.get("customer_engagement"),
            agentDecisionRecords=final_state.get("agent_decision_records")
        )

    except Exception as e:
        logger.error(f"Agent orchestration execution failed: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"AI agent execution error: {str(e)}"
        )
