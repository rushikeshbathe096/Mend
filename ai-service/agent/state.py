import uuid
from typing import Optional, Dict, Any, List, TypedDict
from pydantic import BaseModel, Field
from enum import Enum

class FailureClassEnum(str, Enum):
    INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS"
    BANK_DECLINED = "BANK_DECLINED"
    CARD_EXPIRED = "CARD_EXPIRED"
    AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED"
    NETWORK_FAILURE = "NETWORK_FAILURE"
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    UNKNOWN = "UNKNOWN"

class ActionDecisionEnum(str, Enum):
    RETRY_PAYMENT = "RETRY_PAYMENT"
    CUSTOMER_ACTION_REQUIRED = "CUSTOMER_ACTION_REQUIRED"
    NO_ACTION = "NO_ACTION"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    ESCALATE = "ESCALATE"

class RiskLevelEnum(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"

class AgentNextStepEnum(str, Enum):
    EXECUTE = "EXECUTE"
    HUMAN_APPROVAL = "HUMAN_APPROVAL"
    WAIT_AND_RETRY = "WAIT_AND_RETRY"
    ESCALATE = "ESCALATE"
    FINISHED = "FINISHED"

# Structured Pydantic response models for Specialized Agents

class RiskAssessmentResponse(BaseModel):
    riskLevel: RiskLevelEnum = Field(..., description="Deterministic risk classification")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Risk model confidence score")
    signals: List[str] = Field(default_factory=list, description="Risk & anomaly signals")
    recommendedHandling: str = Field(default="AUTOMATED", description="AUTOMATED or HUMAN_REVIEW")
    humanReviewRequired: bool = Field(default=False, description="True if risk warrants manual intervention")
    reasoningSummary: str = Field(..., description="Concise explainable risk summary")
    modelVersion: str = Field(default="v1.7.0-risk-agent", description="Risk model version")

class AgentDecisionResponse(BaseModel):
    decision: ActionDecisionEnum = Field(..., description="Action decision proposal")
    selectedAction: str = Field(default="RETRY_PAYMENT", description="Selected candidate recovery action")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Agent confidence score")
    riskLevel: RiskLevelEnum = Field(..., description="Risk assessment level")
    reasoningSummary: str = Field(..., description="Concise explainable summary for human operator")
    evidence: List[str] = Field(default_factory=list, description="Supporting evidence signals")
    recommendedDelayMinutes: int = Field(default=0, description="Recommended retry delay in minutes")
    requiresHumanApproval: bool = Field(default=False, description="True if action requires merchant sign-off")
    nextStep: AgentNextStepEnum = Field(..., description="Next state transition direction")
    stopReason: Optional[str] = Field(default=None, description="Explicit stop reason if graph terminates")

class StrategyOptimizationResponse(BaseModel):
    strategy: str = Field(..., description="Recommended recovery strategy name")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Strategy agent confidence score")
    reasoningSummary: str = Field(..., description="Explainable strategy rationale")
    expectedOutcome: str = Field(default="HIGH_PROBABILITY_RECOVERY", description="Expected strategy outcome")
    alternativeStrategy: Optional[str] = Field(default=None, description="Fallback alternative strategy")
    stopCondition: Optional[str] = Field(default=None, description="Strategy termination condition")

class CustomerEngagementResponse(BaseModel):
    engagementNeeded: bool = Field(default=False, description="True if customer contact recommended")
    recommendedAction: str = Field(default="NONE", description="Customer action type, e.g. CUSTOMER_ACTION_REQUIRED")
    channel: str = Field(default="PAYMENT_LINK", description="Recommended dunning channel")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Engagement confidence score")
    reasoningSummary: str = Field(..., description="Engagement rationale summary")
    humanReviewRequired: bool = Field(default=False, description="True if engagement requires manual review")

# LangGraph Multi-Agent State Definition
class RecoveryAgentState(TypedDict, total=False):
    trace_id: str
    correlation_id: str
    merchant_id: str
    campaign_id: str
    payment_id: str
    customer_id: Optional[str]
    subscription_id: Optional[str]
    payment_context: Dict[str, Any]
    customer_context: Dict[str, Any]
    failure_analysis: Dict[str, Any]
    failure_classification: Optional[str]
    classification_confidence: float
    recovery_history: List[Dict[str, Any]]
    merchant_policy: Dict[str, Any]
    available_recovery_actions: List[str]
    previous_decisions: List[Dict[str, Any]]
    previous_outcomes: List[Dict[str, Any]]
    
    # 6 Specialized Agent Decision Outputs
    risk_analysis: Dict[str, Any]
    decision_proposal: Dict[str, Any]
    strategy_recommendation: Dict[str, Any]
    customer_engagement: Dict[str, Any]
    consensus_decision: Dict[str, Any]
    outcome_analysis: Dict[str, Any]
    
    # Aggregated Execution State
    proposed_decision: Optional[str]
    selected_action: Optional[str]
    decision_confidence: float
    confidence: float
    risk_level: str
    reasoning_summary: str
    evidence: List[str]
    compliance_result: Dict[str, Any]
    action_intent_status: Optional[str]
    execution_result: Dict[str, Any]
    outcome: Optional[str]
    attempt_number: int
    next_step: str
    human_review_required: bool
    human_approval_required: bool
    stop_reason: Optional[str]
    model_version: str
    agent_version: str
    agent_trace_id: str
    iteration: int
    fallback_used: bool
    agent_decision_records: List[Dict[str, Any]]

class AgentOrchestrationRequest(BaseModel):
    merchantId: str
    campaignId: str
    paymentId: str
    eventId: Optional[str] = None
    failureCode: Optional[str] = None
    failureReason: Optional[str] = None
    amountInCents: Optional[int] = 0
    attemptCount: Optional[int] = 1
    backendUrl: Optional[str] = "http://localhost:8080"
    traceId: Optional[str] = None
    correlationId: Optional[str] = None

class AgentOrchestrationResponse(BaseModel):
    agentTraceId: str
    traceId: Optional[str] = None
    correlationId: Optional[str] = None
    merchantId: str
    campaignId: str
    paymentId: str
    decision: ActionDecisionEnum
    selectedAction: str
    confidence: float
    riskLevel: RiskLevelEnum
    reasoningSummary: str
    evidence: List[str]
    requiresHumanApproval: bool
    complianceStatus: str
    nextStep: AgentNextStepEnum
    executionResult: Optional[Dict[str, Any]] = None
    iterationCount: int
    fallbackUsed: bool
    stopReason: Optional[str] = None
    modelVersion: str = "v1.7.0-multi-agent"
    agentVersion: str = "v1.7.0"
    strategyRecommendation: Optional[Dict[str, Any]] = None
    customerEngagement: Optional[Dict[str, Any]] = None
    agentDecisionRecords: Optional[List[Dict[str, Any]]] = None
