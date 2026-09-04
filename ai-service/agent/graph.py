import uuid
import logging
from typing import Dict, Any, List
from langgraph.graph import StateGraph, START, END
from langgraph.checkpoint.memory import MemorySaver

from agent.state import (
    RecoveryAgentState,
    AgentDecisionResponse,
    ActionDecisionEnum,
    RiskLevelEnum,
    AgentNextStepEnum
)
from agent.mcp_tools import MendMcpToolSuite
from agent.llm import get_llm

logger = logging.getLogger("mend-ai-service")

MAX_AGENT_ITERATIONS = 3
DEFAULT_CONFIDENCE_THRESHOLD = 0.80

def build_recovery_agent_graph(backend_url: str = "http://localhost:8080"):
    tool_suite = MendMcpToolSuite(backend_url)

    # ==================== AGENT 1: SUPERVISOR AGENT (OBSERVE CONTEXT) ====================
    def supervisor_observe_context(state: RecoveryAgentState) -> RecoveryAgentState:
        merchant_id = state.get("merchant_id", "00000000-0000-0000-0000-000000000001")
        payment_id = state.get("payment_id", "UNKNOWN_PAYMENT")
        customer_id = state.get("customer_id")
        campaign_id = state.get("campaign_id", str(uuid.uuid4()))
        trace_id = state.get("trace_id") or state.get("agent_trace_id") or f"trace_{uuid.uuid4().hex[:12]}"
        correlation_id = state.get("correlation_id") or trace_id

        # Use MCP tools to fetch state safely
        existing_p_ctx = state.get("payment_context") or {}
        p_ctx_res = tool_suite.get_payment_details(merchant_id, payment_id)
        p_ctx = p_ctx_res.get("data", {}) if isinstance(p_ctx_res, dict) else {}
        merged_p_ctx = {**existing_p_ctx, **p_ctx}

        c_ctx = tool_suite.get_payment_history(merchant_id, customer_id)
        policy = tool_suite.get_merchant_policy(merchant_id)
        avail_actions = tool_suite.get_enabled_recovery_actions(merchant_id)
        rec_history = tool_suite.get_recovery_attempts(merchant_id, campaign_id)
        prev_decisions = tool_suite.get_previous_decisions(merchant_id, campaign_id)

        iteration = state.get("iteration", 0) + 1
        attempt_number = state.get("attempt_number", 1)

        # Loop bounding check
        max_attempts = policy.get("maxAttempts", 3)
        if attempt_number > max_attempts:
            return {
                **state,
                "merchant_id": merchant_id,
                "campaign_id": campaign_id,
                "payment_id": payment_id,
                "trace_id": trace_id,
                "correlation_id": correlation_id,
                "agent_trace_id": trace_id,
                "next_step": AgentNextStepEnum.ESCALATE.value,
                "stop_reason": f"Max retry attempts limit ({max_attempts}) reached.",
                "proposed_decision": ActionDecisionEnum.ESCALATE.value,
                "selected_action": "ESCALATE",
                "reasoning_summary": f"Campaign attempt count ({attempt_number}) exceeds max policy limit ({max_attempts}). Escalating.",
                "confidence": 0.99,
                "risk_level": RiskLevelEnum.HIGH.value,
                "iteration": iteration
            }

        return {
            **state,
            "merchant_id": merchant_id,
            "campaign_id": campaign_id,
            "payment_id": payment_id,
            "trace_id": trace_id,
            "correlation_id": correlation_id,
            "agent_trace_id": trace_id,
            "payment_context": merged_p_ctx,
            "customer_context": c_ctx,
            "merchant_policy": policy,
            "available_recovery_actions": avail_actions,
            "recovery_history": rec_history,
            "previous_decisions": prev_decisions,
            "iteration": iteration,
            "attempt_number": attempt_number,
            "model_version": "v1.5.0-langgraph",
            "agent_version": "v1.5.0"
        }

    # ==================== AGENT 2: RECOVERY DECISION AGENT ====================
    def recovery_decision_agent(state: RecoveryAgentState) -> RecoveryAgentState:
        # Check if Supervisor already mandated escalation
        if state.get("next_step") == AgentNextStepEnum.ESCALATE.value:
            return state

        llm = get_llm()
        attempt = state.get("attempt_number", 1)
        amount = state.get("payment_context", {}).get("amountInCents", 0)
        c_ctx = state.get("customer_context", {})
        policy = state.get("merchant_policy", {})
        f_code = state.get("payment_context", {}).get("failureCode", "")
        f_reason = state.get("payment_context", {}).get("failureReason", "")

        # Try LLM Structured Output decision
        if llm:
            try:
                structured_llm = llm.with_structured_output(AgentDecisionResponse)
                prompt = f"""You are Mend's Recovery Decision Agent.
Analyze the payment failure context and propose a recovery decision.

Context:
- Merchant ID: {state['merchant_id']}
- Campaign ID: {state['campaign_id']}
- Attempt Number: {attempt}
- Failure Code: {f_code}
- Failure Reason: {f_reason}
- Transaction Amount (Cents): {amount}
- Customer Risk Score: {c_ctx.get('riskScore')}
- Merchant High-Value Threshold (Cents): {policy.get('highValueThresholdCents', 1000000)}

Select ONE decision from [RETRY_PAYMENT, CUSTOMER_ACTION_REQUIRED, NO_ACTION, REVIEW_REQUIRED, ESCALATE].
Provide a confidence score (0.0 to 1.0) and evidence signals.
If confidence < {DEFAULT_CONFIDENCE_THRESHOLD} or amount > threshold, set requiresHumanApproval=True.
"""
                resp: AgentDecisionResponse = structured_llm.invoke(prompt)
                
                # Check confidence threshold
                req_human = resp.requiresHumanApproval or (resp.confidence < DEFAULT_CONFIDENCE_THRESHOLD)
                next_st = AgentNextStepEnum.HUMAN_APPROVAL.value if req_human else resp.nextStep.value

                return {
                    **state,
                    "proposed_decision": resp.decision.value,
                    "selected_action": resp.selectedAction,
                    "confidence": float(resp.confidence),
                    "decision_confidence": float(resp.confidence),
                    "classification_confidence": float(resp.confidence),
                    "risk_level": resp.riskLevel.value,
                    "reasoning_summary": resp.reasoningSummary,
                    "evidence": resp.evidence,
                    "next_step": next_st,
                    "human_review_required": req_human,
                    "human_approval_required": req_human,
                    "stop_reason": resp.stopReason,
                    "fallback_used": False
                }
            except Exception as e:
                logger.warning(f"LLM Decision Agent invocation failed: {e}. Falling back to Bounded Heuristic Engine.")

        # Bounded Heuristic Decision Engine Fallback
        cls_res = _heuristic_classify(f_code, f_reason)
        cls_name = cls_res["classification"]
        rec_act = cls_res["recommendedAction"]
        conf = cls_res["confidence"]
        reason = cls_res["reason"]

        proposed = ActionDecisionEnum.RETRY_PAYMENT.value
        selected_act = "RETRY_PAYMENT"
        risk = RiskLevelEnum.LOW.value
        human_req = False
        next_st = AgentNextStepEnum.EXECUTE.value
        evidence_list = [f"CLASSIFICATION:{cls_name}", f"ATTEMPT:{attempt}"]

        if cls_name == "CARD_EXPIRED" or rec_act == "CUSTOMER_ACTION_REQUIRED":
            proposed = ActionDecisionEnum.CUSTOMER_ACTION_REQUIRED.value
            selected_act = "CUSTOMER_ACTION_REQUIRED"
            risk = RiskLevelEnum.MEDIUM.value
            next_st = AgentNextStepEnum.EXECUTE.value
            evidence_list.append("CARD_UPDATE_REQUIRED")

        elif cls_name == "BANK_DECLINED" or rec_act == "REVIEW_REQUIRED":
            proposed = ActionDecisionEnum.REVIEW_REQUIRED.value
            selected_act = "REVIEW_REQUIRED"
            risk = RiskLevelEnum.HIGH.value
            human_req = True
            next_st = AgentNextStepEnum.HUMAN_APPROVAL.value
            evidence_list.append("BANK_DECLINED_MANUAL_REVIEW")

        elif cls_name == "UNKNOWN":
            proposed = ActionDecisionEnum.ESCALATE.value
            selected_act = "ESCALATE"
            risk = RiskLevelEnum.HIGH.value
            next_st = AgentNextStepEnum.ESCALATE.value
            evidence_list.append("UNKNOWN_FAILURE_SIGNATURE")

        high_val = policy.get("highValueThresholdCents", 1000000)
        if amount > high_val:
            human_req = True
            risk = RiskLevelEnum.HIGH.value
            next_st = AgentNextStepEnum.HUMAN_APPROVAL.value
            evidence_list.append(f"HIGH_VALUE_TRANSACTION ({amount} > {high_val})")

        if conf < DEFAULT_CONFIDENCE_THRESHOLD:
            human_req = True
            next_st = AgentNextStepEnum.HUMAN_APPROVAL.value
            evidence_list.append(f"LOW_CONFIDENCE_SCORE ({conf} < {DEFAULT_CONFIDENCE_THRESHOLD})")

        return {
            **state,
            "proposed_decision": proposed,
            "selected_action": selected_act,
            "confidence": conf,
            "decision_confidence": conf,
            "classification_confidence": conf,
            "risk_level": risk,
            "reasoning_summary": reason,
            "evidence": evidence_list,
            "next_step": next_st,
            "human_review_required": human_req,
            "human_approval_required": human_req,
            "fallback_used": True
        }

    # ==================== COMPLIANCE GATE (SPRING BOOT BOUNDARY) ====================
    def compliance_check_node(state: RecoveryAgentState) -> RecoveryAgentState:
        m_id = state["merchant_id"]
        c_id = state["campaign_id"]
        action = state.get("selected_action") or state.get("proposed_decision", "RETRY_PAYMENT")
        attempt = state.get("attempt_number", 1)
        amount = state.get("payment_context", {}).get("amountInCents", 0)

        comp = tool_suite.check_compliance(m_id, c_id, action, attempt, amount)

        if comp.get("status") == "HUMAN_REVIEW_REQUIRED" or state.get("human_review_required"):
            return {
                **state,
                "compliance_result": comp,
                "human_review_required": True,
                "human_approval_required": True,
                "next_step": AgentNextStepEnum.HUMAN_APPROVAL.value
            }

        if not comp.get("allowed"):
            return {
                **state,
                "compliance_result": comp,
                "next_step": AgentNextStepEnum.ESCALATE.value,
                "stop_reason": comp.get("message", "Compliance block")
            }

        return {
            **state,
            "compliance_result": comp,
            "next_step": AgentNextStepEnum.EXECUTE.value
        }

    def route_after_compliance(state: RecoveryAgentState) -> str:
        next_s = state.get("next_step")
        if next_s == AgentNextStepEnum.HUMAN_APPROVAL.value or state.get("human_review_required"):
            return "human_approval_node"
        elif next_s == AgentNextStepEnum.ESCALATE.value:
            return "escalate_node"
        else:
            return "create_action_intent_node"

    # ==================== ACTION INTENT & EXECUTION NODES ====================
    def escalate_node(state: RecoveryAgentState) -> RecoveryAgentState:
        c_id = state["campaign_id"]
        m_id = state["merchant_id"]
        reason = state.get("compliance_result", {}).get("message") or state.get("reasoning_summary", "Escalated by AI Agent")
        res = tool_suite.request_human_review(m_id, c_id, reason)
        return {
            **state,
            "execution_result": res,
            "next_step": AgentNextStepEnum.FINISHED.value,
            "stop_reason": reason
        }

    def human_approval_node(state: RecoveryAgentState) -> RecoveryAgentState:
        m_id = state["merchant_id"]
        c_id = state["campaign_id"]
        reason = state.get("reasoning_summary", "Action intent held for merchant human approval.")
        tool_suite.request_human_review(m_id, c_id, reason)
        return {
            **state,
            "execution_result": {
                "status": "WAITING_FOR_APPROVAL",
                "campaignId": c_id,
                "message": reason
            },
            "next_step": AgentNextStepEnum.HUMAN_APPROVAL.value,
            "stop_reason": "WAITING_FOR_MERCHANT_HUMAN_APPROVAL"
        }

    def create_action_intent_node(state: RecoveryAgentState) -> RecoveryAgentState:
        m_id = state["merchant_id"]
        c_id = state["campaign_id"]
        act = state.get("selected_action") or state.get("proposed_decision", "RETRY_PAYMENT")
        intent_res = tool_suite.create_action_intent(m_id, c_id, act)
        return {
            **state,
            "execution_result": intent_res,
            "action_intent_status": intent_res.get("status", "CREATED")
        }

    def execute_action_node(state: RecoveryAgentState) -> RecoveryAgentState:
        m_id = state["merchant_id"]
        intent_id = state.get("execution_result", {}).get("intentId", f"intent_{state['campaign_id'][:8]}")
        exec_res = tool_suite.get_execution_result(m_id, intent_id)
        return {
            **state,
            "execution_result": exec_res
        }

    def observe_outcome_node(state: RecoveryAgentState) -> RecoveryAgentState:
        m_id = state["merchant_id"]
        intent_id = state.get("execution_result", {}).get("intentId", "intent_1")
        outcome = tool_suite.get_recovery_outcome(m_id, intent_id)
        return {
            **state,
            "execution_result": {**state.get("execution_result", {}), "outcome": outcome}
        }

    # ==================== AGENT 3: OUTCOME ANALYSIS AGENT ====================
    def outcome_analysis_agent(state: RecoveryAgentState) -> RecoveryAgentState:
        exec_status = state.get("execution_result", {}).get("status")
        outcome_obj = state.get("execution_result", {}).get("outcome", {})
        outcome_val = outcome_obj.get("outcome", "UNKNOWN") if isinstance(outcome_obj, dict) else "UNKNOWN"

        if exec_status == "SUCCEEDED" or outcome_val == "RECOVERED":
            return {
                **state,
                "outcome": "RECOVERED",
                "next_step": AgentNextStepEnum.FINISHED.value,
                "stop_reason": "PAYMENT_RECOVERY_SUCCESSFUL"
            }

        # Check iteration loop boundary
        if state.get("iteration", 1) >= MAX_AGENT_ITERATIONS:
            return {
                **state,
                "outcome": "FAILED",
                "next_step": AgentNextStepEnum.FINISHED.value,
                "stop_reason": f"Max agent iterations ({MAX_AGENT_ITERATIONS}) reached."
            }

        policy = state.get("merchant_policy", {})
        max_attempts = policy.get("maxAttempts", 3)
        current_attempt = state.get("attempt_number", 1)

        if current_attempt >= max_attempts:
            return {
                **state,
                "outcome": "MAX_ATTEMPTS_EXCEEDED",
                "next_step": AgentNextStepEnum.FINISHED.value,
                "stop_reason": f"Retry budget exhausted after attempt {current_attempt}."
            }

        # Recommend re-evaluation for next bounded attempt
        return {
            **state,
            "attempt_number": current_attempt + 1,
            "next_step": AgentNextStepEnum.WAIT_AND_RETRY.value
        }

    # ==================== AGENT 1: SUPERVISOR RE-EVALUATION ====================
    def supervisor_reevaluate(state: RecoveryAgentState) -> RecoveryAgentState:
        next_s = state.get("next_step")
        if next_s == AgentNextStepEnum.FINISHED.value:
            return state

        return {
            **state,
            "reasoning_summary": f"Supervisor re-evaluating recovery loop for attempt #{state.get('attempt_number', 1)}"
        }

    def route_after_reevaluate(state: RecoveryAgentState) -> str:
        if state.get("next_step") == AgentNextStepEnum.WAIT_AND_RETRY.value:
            return "recovery_decision_agent"
        return END

    # ==================== ASSEMBLE LANGGRAPH STATE GRAPH ====================
    workflow = StateGraph(RecoveryAgentState)

    workflow.add_node("supervisor_observe_context", supervisor_observe_context)
    workflow.add_node("recovery_decision_agent", recovery_decision_agent)
    workflow.add_node("compliance_check_node", compliance_check_node)
    workflow.add_node("escalate_node", escalate_node)
    workflow.add_node("human_approval_node", human_approval_node)
    workflow.add_node("create_action_intent_node", create_action_intent_node)
    workflow.add_node("execute_action_node", execute_action_node)
    workflow.add_node("observe_outcome_node", observe_outcome_node)
    workflow.add_node("outcome_analysis_agent", outcome_analysis_agent)
    workflow.add_node("supervisor_reevaluate", supervisor_reevaluate)

    workflow.add_edge(START, "supervisor_observe_context")
    workflow.add_edge("supervisor_observe_context", "recovery_decision_agent")
    workflow.add_edge("recovery_decision_agent", "compliance_check_node")

    workflow.add_conditional_edges(
        "compliance_check_node",
        route_after_compliance,
        {
            "human_approval_node": "human_approval_node",
            "escalate_node": "escalate_node",
            "create_action_intent_node": "create_action_intent_node"
        }
    )

    workflow.add_edge("create_action_intent_node", "execute_action_node")
    workflow.add_edge("execute_action_node", "observe_outcome_node")
    workflow.add_edge("observe_outcome_node", "outcome_analysis_agent")
    workflow.add_edge("outcome_analysis_agent", "supervisor_reevaluate")

    workflow.add_conditional_edges(
        "supervisor_reevaluate",
        route_after_reevaluate,
        {
            "recovery_decision_agent": "recovery_decision_agent",
            END: END
        }
    )

    workflow.add_edge("escalate_node", END)
    workflow.add_edge("human_approval_node", END)

    # Attach in-memory checkpointer for durable state saving and resumption
    checkpointer = MemorySaver()
    return workflow.compile(checkpointer=checkpointer)


def _heuristic_classify(failure_code: str, failure_reason: str) -> Dict[str, Any]:
    code = (failure_code or "").lower()
    reason = (failure_reason or "").lower()
    combined = f"{code} {reason}"

    if any(k in combined for k in ["insufficient_funds", "low_balance", "insufficient_balance", "insufficient funds"]):
        return {
            "classification": "INSUFFICIENT_FUNDS",
            "confidence": 0.95,
            "recommendedAction": "RETRY_LATER",
            "reason": "Customer account balance temporarily insufficient."
        }
    elif any(k in combined for k in ["expired_card", "card_expired", "expiry"]):
        return {
            "classification": "CARD_EXPIRED",
            "confidence": 0.95,
            "recommendedAction": "CUSTOMER_ACTION_REQUIRED",
            "reason": "Card has expired; customer update needed."
        }
    elif any(k in combined for k in ["bank_declined", "do_not_honor"]):
        return {
            "classification": "BANK_DECLINED",
            "confidence": 0.90,
            "recommendedAction": "REVIEW_REQUIRED",
            "reason": "Issuing bank declined transaction."
        }
    elif any(k in combined for k in ["network_failure", "timeout", "system_error"]):
        return {
            "classification": "NETWORK_FAILURE",
            "confidence": 0.85,
            "recommendedAction": "RETRY_IMMEDIATELY",
            "reason": "Transient gateway network timeout."
        }
    else:
        return {
            "classification": "UNKNOWN",
            "confidence": 0.40,
            "recommendedAction": "REVIEW_REQUIRED",
            "reason": "Unrecognized failure pattern."
        }
