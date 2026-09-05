import uuid
import logging
import time
from typing import Dict, Any, List, Optional
from langgraph.graph import StateGraph, START, END

from agent.state import (
    RecoveryAgentState,
    AgentDecisionResponse,
    RiskAssessmentResponse,
    StrategyOptimizationResponse,
    CustomerEngagementResponse,
    ActionDecisionEnum,
    RiskLevelEnum,
    AgentNextStepEnum
)
from agent.mcp_tools import MendMcpToolSuite
from agent.checkpointer import DurableMemorySaver
from agent.llm import get_llm

logger = logging.getLogger("mend-ai-service")

MAX_AGENT_ITERATIONS = 3
DEFAULT_CONFIDENCE_THRESHOLD = 0.80

_global_checkpointer = None

def get_shared_checkpointer():
    global _global_checkpointer
    if _global_checkpointer is None:
        _global_checkpointer = DurableMemorySaver()
    return _global_checkpointer

def build_recovery_agent_graph(backend_url: str = "http://localhost:8080", checkpointer=None):
    tool_suite = MendMcpToolSuite(backend_url)

    # ==================== AGENT 1: RECOVERY SUPERVISOR AGENT (CONTEXT OBSERVATION) ====================
    def supervisor_observe_context(state: RecoveryAgentState) -> RecoveryAgentState:
        merchant_id = state.get("merchant_id", "00000000-0000-0000-0000-000000000001")
        payment_id = state.get("payment_id", "UNKNOWN_PAYMENT")
        customer_id = state.get("customer_id")
        campaign_id = state.get("campaign_id", str(uuid.uuid4()))
        trace_id = state.get("trace_id") or state.get("agent_trace_id") or f"trace_{uuid.uuid4().hex[:12]}"
        correlation_id = state.get("correlation_id") or trace_id

        # Use MCP tools to fetch state securely
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
                "iteration": iteration,
                "agent_decision_records": state.get("agent_decision_records", [])
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
            "agent_decision_records": state.get("agent_decision_records", []),
            "model_version": "v1.7.0-multi-agent",
            "agent_version": "v1.7.0"
        }

    # ==================== AGENT 2: RISK & FRAUD AGENT ====================
    def risk_and_fraud_agent(state: RecoveryAgentState) -> RecoveryAgentState:
        if state.get("next_step") == AgentNextStepEnum.ESCALATE.value:
            return state

        merchant_id = state["merchant_id"]
        customer_id = state.get("customer_id")
        amount = state.get("payment_context", {}).get("amountInCents", 0)
        attempt = state.get("attempt_number", 1)
        policy = state.get("merchant_policy", {})

        risk_signals = tool_suite.get_risk_signals(merchant_id, customer_id)
        risk_history = tool_suite.get_customer_risk_history(merchant_id, customer_id)

        signals_list = risk_signals.get("signals", [])
        score = risk_signals.get("score", 0.12)
        disputes = risk_history.get("disputeCount", 0)

        # Deterministic Risk Computation
        high_val_thresh = policy.get("highValueThresholdCents", 1000000)
        risk_level = RiskLevelEnum.LOW.value
        human_req = False
        handling = "AUTOMATED"
        reasoning = "Low risk transaction; safe for automated recovery orchestration."

        if amount > high_val_thresh:
            risk_level = RiskLevelEnum.HIGH.value
            human_req = True
            handling = "HUMAN_REVIEW"
            signals_list.append(f"HIGH_VALUE_TRANSACTION ({amount} > {high_val_thresh})")
            reasoning = f"Payment amount ({amount}) exceeds merchant high-value threshold. Mandatory human review."
        elif disputes > 0 or risk_history.get("suspiciousPatternsDetected"):
            risk_level = RiskLevelEnum.HIGH.value
            human_req = True
            handling = "HUMAN_REVIEW"
            signals_list.append("CUSTOMER_DISPUTE_HISTORY")
            reasoning = "Customer has recorded disputes or chargeback alerts."
        elif attempt >= 3:
            risk_level = RiskLevelEnum.MEDIUM.value
            signals_list.append("MULTIPLE_RETRY_ATTEMPTS")
            reasoning = f"Multiple retry attempts ({attempt}) reached."

        risk_out = {
            "agentName": "RiskAndFraudAgent",
            "riskLevel": risk_level,
            "confidence": 0.95 if risk_level == RiskLevelEnum.LOW.value else 0.90,
            "signals": signals_list,
            "recommendedHandling": handling,
            "humanReviewRequired": human_req,
            "reasoningSummary": reasoning,
            "modelVersion": "v1.7.0-risk-agent",
            "timestamp": time.time()
        }

        records = list(state.get("agent_decision_records", []))
        records.append(risk_out)

        return {
            **state,
            "risk_analysis": risk_out,
            "risk_level": risk_level,
            "human_review_required": state.get("human_review_required", False) or human_req,
            "agent_decision_records": records
        }

    # ==================== AGENT 3: RECOVERY DECISION AGENT ====================
    def recovery_decision_agent(state: RecoveryAgentState) -> RecoveryAgentState:
        if state.get("next_step") == AgentNextStepEnum.ESCALATE.value:
            return state

        llm = get_llm()
        attempt = state.get("attempt_number", 1)
        amount = state.get("payment_context", {}).get("amountInCents", 0)
        c_ctx = state.get("customer_context", {})
        policy = state.get("merchant_policy", {})
        f_code = state.get("payment_context", {}).get("failureCode", "")
        f_reason = state.get("payment_context", {}).get("failureReason", "")

        proposed = ActionDecisionEnum.RETRY_PAYMENT.value
        selected_act = "RETRY_PAYMENT"
        conf = 0.90
        human_req = False
        reason = "Standard retry recommendation based on payment failure pattern."
        evidence_list = [f"ATTEMPT:{attempt}"]

        cls_res = _heuristic_classify(f_code, f_reason)
        cls_name = cls_res["classification"]
        evidence_list.append(f"CLASSIFICATION:{cls_name}")

        if cls_name == "CARD_EXPIRED":
            proposed = ActionDecisionEnum.CUSTOMER_ACTION_REQUIRED.value
            selected_act = "CUSTOMER_ACTION_REQUIRED"
            reason = "Card has expired; customer dunning action recommended."
            conf = 0.95
        elif cls_name == "BANK_DECLINED":
            proposed = ActionDecisionEnum.REVIEW_REQUIRED.value
            selected_act = "REVIEW_REQUIRED"
            reason = "Bank declined transaction; manual review proposed."
            human_req = True
            conf = 0.90
        elif cls_name == "UNKNOWN":
            proposed = ActionDecisionEnum.ESCALATE.value
            selected_act = "ESCALATE"
            reason = "Unrecognized failure code signature."
            human_req = True
            conf = 0.50

        # LLM override if available
        if llm:
            try:
                structured_llm = llm.with_structured_output(AgentDecisionResponse)
                prompt = f"""You are Mend's Recovery Decision Agent.
Context:
- Failure Code: {f_code}
- Failure Reason: {f_reason}
- Attempt Number: {attempt}
- Amount: {amount}
- Customer Risk Score: {c_ctx.get('riskScore')}

Propose ONE decision from [RETRY_PAYMENT, CUSTOMER_ACTION_REQUIRED, NO_ACTION, REVIEW_REQUIRED, ESCALATE].
"""
                resp: AgentDecisionResponse = structured_llm.invoke(prompt)
                proposed = resp.decision.value
                selected_act = resp.selectedAction
                conf = float(resp.confidence)
                reason = resp.reasoningSummary
                evidence_list = resp.evidence
                human_req = resp.requiresHumanApproval
            except Exception as e:
                logger.warning(f"LLM Recovery Decision Agent failed: {e}. Using heuristic decision.")

        dec_out = {
            "agentName": "RecoveryDecisionAgent",
            "decision": proposed,
            "selectedAction": selected_act,
            "confidence": conf,
            "reasoningSummary": reason,
            "evidence": evidence_list,
            "requiresHumanApproval": human_req,
            "modelVersion": "v1.7.0-decision-agent",
            "timestamp": time.time()
        }

        records = list(state.get("agent_decision_records", []))
        records.append(dec_out)

        return {
            **state,
            "decision_proposal": dec_out,
            "proposed_decision": proposed,
            "selected_action": selected_act,
            "decision_confidence": conf,
            "confidence": conf,
            "reasoning_summary": reason,
            "evidence": evidence_list,
            "human_review_required": state.get("human_review_required", False) or human_req,
            "agent_decision_records": records
        }

    # ==================== AGENT 4: RECOVERY STRATEGY AGENT ====================
    def recovery_strategy_agent(state: RecoveryAgentState) -> RecoveryAgentState:
        if state.get("next_step") == AgentNextStepEnum.ESCALATE.value:
            return state

        merchant_id = state["merchant_id"]
        f_code = state.get("payment_context", {}).get("failureCode", "")
        f_reason = state.get("payment_context", {}).get("failureReason", "")
        cls_res = _heuristic_classify(f_code, f_reason)
        f_class = cls_res["classification"]

        perf = tool_suite.get_strategy_performance(merchant_id, f_class)
        avail_strats = tool_suite.get_available_recovery_strategies(merchant_id)

        rec_strategy = "PAYMENT_RETRY"
        expected_out = "HIGH_PROBABILITY_RECOVERY"
        alt_strategy = "CUSTOMER_ACTION_REQUIRED"
        reason = "Payment retry yields optimal recovery rate for transient payment failures."

        if f_class == "CARD_EXPIRED":
            rec_strategy = "CUSTOMER_ACTION_REQUIRED"
            alt_strategy = "REVIEW_REQUIRED"
            reason = "Customer action strategy yields 52% recovery rate for expired card failures."
        elif f_class == "BANK_DECLINED":
            rec_strategy = "REVIEW_REQUIRED"
            alt_strategy = "CUSTOMER_ACTION_REQUIRED"
            reason = "Manual review strategy yields 60% recovery rate for bank declined failures."

        strat_out = {
            "agentName": "RecoveryStrategyAgent",
            "strategy": rec_strategy,
            "confidence": 0.89,
            "reasoningSummary": reason,
            "expectedOutcome": expected_out,
            "alternativeStrategy": alt_strategy,
            "availableStrategies": avail_strats,
            "historicalPerformance": perf.get("strategies", {}),
            "modelVersion": "v1.7.0-strategy-agent",
            "timestamp": time.time()
        }

        records = list(state.get("agent_decision_records", []))
        records.append(strat_out)

        return {
            **state,
            "strategy_recommendation": strat_out,
            "agent_decision_records": records
        }

    # ==================== AGENT 5: CUSTOMER ENGAGEMENT AGENT ====================
    def customer_engagement_agent(state: RecoveryAgentState) -> RecoveryAgentState:
        if state.get("next_step") == AgentNextStepEnum.ESCALATE.value:
            return state

        merchant_id = state["merchant_id"]
        customer_id = state.get("customer_id")
        dec_proposal = state.get("decision_proposal", {}).get("decision")
        strat_proposal = state.get("strategy_recommendation", {}).get("strategy")

        contact_hist = tool_suite.get_customer_contact_history(merchant_id, customer_id)
        allowed_actions = tool_suite.get_allowed_customer_actions(merchant_id)

        engagement_needed = False
        rec_action = "NONE"
        channel = "PAYMENT_LINK"
        human_req = False
        reason = "No customer engagement required for automated background retry."

        if dec_proposal == "CUSTOMER_ACTION_REQUIRED" or strat_proposal == "CUSTOMER_ACTION_REQUIRED":
            engagement_needed = True
            rec_action = "CUSTOMER_ACTION_REQUIRED"
            channel = contact_hist.get("preferredChannel", "PAYMENT_LINK")
            reason = f"Customer action required; recommending dunning link via {channel}."

        eng_out = {
            "agentName": "CustomerEngagementAgent",
            "engagementNeeded": engagement_needed,
            "recommendedAction": rec_action,
            "channel": channel,
            "confidence": 0.92,
            "reasoningSummary": reason,
            "humanReviewRequired": human_req,
            "allowedActions": allowed_actions,
            "modelVersion": "v1.7.0-engagement-agent",
            "timestamp": time.time()
        }

        records = list(state.get("agent_decision_records", []))
        records.append(eng_out)

        return {
            **state,
            "customer_engagement": eng_out,
            "agent_decision_records": records
        }

    # ==================== SUPERVISOR CONSENSUS & DECISION AGGREGATOR ====================
    def supervisor_consensus_node(state: RecoveryAgentState) -> RecoveryAgentState:
        if state.get("next_step") == AgentNextStepEnum.ESCALATE.value:
            return state

        risk_info = state.get("risk_analysis", {})
        dec_info = state.get("decision_proposal", {})
        strat_info = state.get("strategy_recommendation", {})
        eng_info = state.get("customer_engagement", {})

        risk_level = risk_info.get("riskLevel", RiskLevelEnum.LOW.value)
        dec = dec_info.get("decision", ActionDecisionEnum.RETRY_PAYMENT.value)
        strat = strat_info.get("strategy", "PAYMENT_RETRY")
        eng_act = eng_info.get("recommendedAction", "NONE")

        risk_human_req = risk_info.get("humanReviewRequired", False)
        dec_human_req = dec_info.get("requiresHumanApproval", False)
        eng_human_req = eng_info.get("humanReviewRequired", False)

        # Consensus & Disagreement Resolution
        consensus_status = "AGREED"
        final_action = dec_info.get("selectedAction", "RETRY_PAYMENT")
        final_next_step = AgentNextStepEnum.EXECUTE.value
        human_review_req = risk_human_req or dec_human_req or eng_human_req
        reasoning = dec_info.get("reasoningSummary", "Consensus reached among agents.")

        # Disagreement rule: High Risk vs Automated Retry proposal
        if risk_level in [RiskLevelEnum.HIGH.value, RiskLevelEnum.CRITICAL.value]:
            consensus_status = "DISAGREEMENT_HIGH_RISK"
            final_action = "REVIEW_REQUIRED"
            final_next_step = AgentNextStepEnum.HUMAN_APPROVAL.value
            human_review_req = True
            reasoning = f"Risk Agent identified {risk_level} risk level. Mandatory routing to merchant human review."
        elif dec == "REVIEW_REQUIRED" or strat == "REVIEW_REQUIRED":
            consensus_status = "MANUAL_REVIEW_MANDATED"
            final_action = "REVIEW_REQUIRED"
            final_next_step = AgentNextStepEnum.HUMAN_APPROVAL.value
            human_review_req = True
            reasoning = "Specialized agent requested operational manual review."
        elif dec == "CUSTOMER_ACTION_REQUIRED" or eng_act == "CUSTOMER_ACTION_REQUIRED" or strat == "CUSTOMER_ACTION_REQUIRED":
            consensus_status = "AGREED_CUSTOMER_ACTION"
            final_action = "CUSTOMER_ACTION_REQUIRED"
            final_next_step = AgentNextStepEnum.EXECUTE.value
            reasoning = "Consensus reached: Customer dunning action required."

        if human_review_req:
            final_next_step = AgentNextStepEnum.HUMAN_APPROVAL.value

        consensus_out = {
            "agentName": "SupervisorConsensus",
            "consensusStatus": consensus_status,
            "selectedAction": final_action,
            "riskLevel": risk_level,
            "humanReviewRequired": human_review_req,
            "reasoningSummary": reasoning,
            "timestamp": time.time()
        }

        records = list(state.get("agent_decision_records", []))
        records.append(consensus_out)

        return {
            **state,
            "consensus_decision": consensus_out,
            "proposed_decision": dec,
            "selected_action": final_action,
            "risk_level": risk_level,
            "reasoning_summary": reasoning,
            "human_review_required": human_review_req,
            "human_approval_required": human_review_req,
            "next_step": final_next_step,
            "agent_decision_records": records
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

    # ==================== AGENT 6: OUTCOME ANALYSIS AGENT ====================
    def outcome_analysis_agent(state: RecoveryAgentState) -> RecoveryAgentState:
        exec_status = state.get("execution_result", {}).get("status")
        outcome_obj = state.get("execution_result", {}).get("outcome", {})
        outcome_val = outcome_obj.get("outcome", "UNKNOWN") if isinstance(outcome_obj, dict) else "UNKNOWN"

        outcome_record = {
            "agentName": "OutcomeAnalysisAgent",
            "executionStatus": exec_status,
            "reconciledOutcome": outcome_val,
            "timestamp": time.time()
        }

        records = list(state.get("agent_decision_records", []))
        records.append(outcome_record)

        if exec_status == "SUCCEEDED" or outcome_val == "RECOVERED":
            return {
                **state,
                "outcome_analysis": outcome_record,
                "outcome": "RECOVERED",
                "next_step": AgentNextStepEnum.FINISHED.value,
                "stop_reason": "PAYMENT_RECOVERY_SUCCESSFUL",
                "agent_decision_records": records
            }

        if exec_status == "PENDING" or outcome_val == "PENDING":
            return {
                **state,
                "outcome_analysis": outcome_record,
                "outcome": "PENDING_EXECUTION",
                "next_step": AgentNextStepEnum.FINISHED.value,
                "stop_reason": None,
                "agent_decision_records": records
            }

        if state.get("iteration", 1) >= MAX_AGENT_ITERATIONS:
            return {
                **state,
                "outcome_analysis": outcome_record,
                "outcome": "FAILED",
                "next_step": AgentNextStepEnum.FINISHED.value,
                "stop_reason": f"Max agent iterations ({MAX_AGENT_ITERATIONS}) reached.",
                "agent_decision_records": records
            }

        policy = state.get("merchant_policy", {})
        max_attempts = policy.get("maxAttempts", 3)
        current_attempt = state.get("attempt_number", 1)

        if current_attempt >= max_attempts:
            return {
                **state,
                "outcome_analysis": outcome_record,
                "outcome": "MAX_ATTEMPTS_EXCEEDED",
                "next_step": AgentNextStepEnum.FINISHED.value,
                "stop_reason": f"Retry budget exhausted after attempt {current_attempt}.",
                "agent_decision_records": records
            }

        return {
            **state,
            "outcome_analysis": outcome_record,
            "next_step": AgentNextStepEnum.FINISHED.value,
            "stop_reason": None,
            "agent_decision_records": records
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
            return "risk_and_fraud_agent"
        return END

    # ==================== ASSEMBLE LANGGRAPH STATE GRAPH ====================
    workflow = StateGraph(RecoveryAgentState)

    workflow.add_node("supervisor_observe_context", supervisor_observe_context)
    workflow.add_node("risk_and_fraud_agent", risk_and_fraud_agent)
    workflow.add_node("recovery_decision_agent", recovery_decision_agent)
    workflow.add_node("recovery_strategy_agent", recovery_strategy_agent)
    workflow.add_node("customer_engagement_agent", customer_engagement_agent)
    workflow.add_node("supervisor_consensus_node", supervisor_consensus_node)
    workflow.add_node("compliance_check_node", compliance_check_node)
    workflow.add_node("escalate_node", escalate_node)
    workflow.add_node("human_approval_node", human_approval_node)
    workflow.add_node("create_action_intent_node", create_action_intent_node)
    workflow.add_node("execute_action_node", execute_action_node)
    workflow.add_node("observe_outcome_node", observe_outcome_node)
    workflow.add_node("outcome_analysis_agent", outcome_analysis_agent)
    workflow.add_node("supervisor_reevaluate", supervisor_reevaluate)

    # Multi-Agent Sequential Analysis Sequence
    workflow.add_edge(START, "supervisor_observe_context")
    workflow.add_edge("supervisor_observe_context", "risk_and_fraud_agent")
    workflow.add_edge("risk_and_fraud_agent", "recovery_decision_agent")
    workflow.add_edge("recovery_decision_agent", "recovery_strategy_agent")
    workflow.add_edge("recovery_strategy_agent", "customer_engagement_agent")
    workflow.add_edge("customer_engagement_agent", "supervisor_consensus_node")
    workflow.add_edge("supervisor_consensus_node", "compliance_check_node")

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
            "risk_and_fraud_agent": "risk_and_fraud_agent",
            END: END
        }
    )

    workflow.add_edge("escalate_node", END)
    workflow.add_edge("human_approval_node", END)

    saver = checkpointer or get_shared_checkpointer()
    return workflow.compile(checkpointer=saver)


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
