package com.mend.agent;

import com.mend.client.AiClassificationClient;
import com.mend.dto.ai.AgentOrchestrationRequestDto;
import com.mend.dto.ai.AgentOrchestrationResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class DefaultAgentDecisionEngine implements AgentDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentDecisionEngine.class);

    private final AiClassificationClient aiClassificationClient;

    @Autowired
    public DefaultAgentDecisionEngine(@Autowired(required = false) AiClassificationClient aiClassificationClient) {
        this.aiClassificationClient = aiClassificationClient;
    }

    @Override
    public AgentDecision decide(AgentContext context) {
        log.info("AgentDecisionEngine evaluating context for campaignId='{}', merchantId='{}', attempt={}/{}",
                context.campaignId(), context.merchantId(), context.attemptCount(), context.maxAttempts());

        // 1. Check Explicit Stop Conditions First
        if (context.attemptCount() >= context.maxAttempts()) {
            log.info("Max attempts reached ({}/{}) for campaignId='{}'. Decision: STOP_RECOVERY",
                    context.attemptCount(), context.maxAttempts(), context.campaignId());
            return AgentDecision.create(
                    context.campaignId(),
                    context.merchantId(),
                    "STOP_RECOVERY",
                    "NO_ACTION",
                    new BigDecimal("1.00"),
                    "Maximum recovery attempt limit (" + context.maxAttempts() + ") reached.",
                    List.of("ATTEMPT_EXHAUSTED", "MAX_ATTEMPTS:" + context.maxAttempts()),
                    "FINISHED",
                    "MAXIMUM_ATTEMPTS_REACHED",
                    "v2.0-deterministic",
                    false,
                    "COMPLIANCE_ALLOWED"
            );
        }

        if ("RECOVERED".equalsIgnoreCase(context.campaignState()) || "PAID".equalsIgnoreCase(context.campaignState())) {
            return AgentDecision.create(
                    context.campaignId(),
                    context.merchantId(),
                    "RECOVERED",
                    "NO_ACTION",
                    new BigDecimal("1.00"),
                    "Payment already successfully recovered.",
                    List.of("STATE:RECOVERED"),
                    "FINISHED",
                    "PAYMENT_RECOVERED",
                    "v2.0-deterministic",
                    false,
                    "COMPLIANCE_ALLOWED"
            );
        }

        // 2. Try Calling AI / LangGraph Agent Service
        if (aiClassificationClient != null) {
            try {
                AgentOrchestrationRequestDto request = new AgentOrchestrationRequestDto(
                        context.merchantId().toString(),
                        context.campaignId().toString(),
                        context.paymentId(),
                        UUID.randomUUID().toString(),
                        context.failureCode(),
                        context.failureReason(),
                        context.amountInCents(),
                        context.attemptCount(),
                        "http://localhost:8080"
                );

                AgentOrchestrationResponseDto response = aiClassificationClient.orchestrateAgent(request);

                if (response != null && response.decision() != null) {
                    log.info("AI Agent Service returned decision='{}', confidence={}, traceId='{}'",
                            response.decision(), response.confidence(), response.agentTraceId());

                    boolean reviewRequired = response.requiresHumanApproval() ||
                            (response.confidence() != null && response.confidence().compareTo(new BigDecimal("0.70")) < 0);

                    return AgentDecision.create(
                            context.campaignId(),
                            context.merchantId(),
                            response.decision(),
                            response.decision(),
                            response.confidence() != null ? response.confidence() : new BigDecimal("0.85"),
                            response.reasoningSummary() != null ? response.reasoningSummary() : "AI strategy recommendation",
                            response.evidence() != null ? response.evidence() : List.of("AI_SERVICE_ORCHESTRATED"),
                            response.nextStep() != null ? response.nextStep() : "EXECUTE",
                            response.stopReason(),
                            "v2.0-langgraph",
                            reviewRequired,
                            response.complianceStatus() != null ? response.complianceStatus() : "COMPLIANCE_ALLOWED"
                    );

                }
            } catch (Exception e) {
                log.warn("AI Agent Service call failed for campaignId='{}': {}. Falling back to deterministic engine.",
                        context.campaignId(), e.getMessage());
            }
        }

        // 3. Fallback Deterministic Engine
        return fallbackDeterministicDecide(context);
    }

    private AgentDecision fallbackDeterministicDecide(AgentContext context) {
        String code = context.failureCode() != null ? context.failureCode().toLowerCase() : "";

        if (code.contains("insufficient_funds") || code.contains("low_balance")) {
            return AgentDecision.create(
                    context.campaignId(),
                    context.merchantId(),
                    "WAIT_AND_RETRY",
                    "RETRY_PAYMENT",
                    new BigDecimal("0.90"),
                    "Deterministic fallback: Insufficient funds detected; wait and retry recommended.",
                    List.of("FALLBACK_RULE", "CODE:INSUFFICIENT_FUNDS"),
                    "EXECUTE",
                    null,
                    "v2.0-fallback-rule",
                    false,
                    "COMPLIANCE_ALLOWED"
            );
        } else if (code.contains("expired_card") || code.contains("card_expired")) {
            return AgentDecision.create(
                    context.campaignId(),
                    context.merchantId(),
                    "REQUEST_CUSTOMER_ACTION",
                    "SEND_CUSTOMER_EMAIL",
                    new BigDecimal("0.88"),
                    "Deterministic fallback: Card expired; request customer payment details update.",
                    List.of("FALLBACK_RULE", "CODE:EXPIRED_CARD"),
                    "EXECUTE",
                    null,
                    "v2.0-fallback-rule",
                    false,
                    "COMPLIANCE_ALLOWED"
            );
        }

        return AgentDecision.create(
                context.campaignId(),
                context.merchantId(),
                "RETRY_PAYMENT",
                "RETRY_PAYMENT",
                new BigDecimal("0.80"),
                "Deterministic fallback: Default recovery retry.",
                List.of("FALLBACK_RULE", "DEFAULT"),
                "EXECUTE",
                null,
                "v2.0-fallback-rule",
                false,
                "COMPLIANCE_ALLOWED"
        );
    }
}
