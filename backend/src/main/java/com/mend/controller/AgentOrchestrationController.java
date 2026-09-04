package com.mend.controller;

import com.mend.client.AiClassificationClient;
import com.mend.dto.ai.AgentOrchestrationRequestDto;
import com.mend.dto.ai.AgentOrchestrationResponseDto;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentOrchestrationController {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrationController.class);

    private final AiClassificationClient aiClassificationClient;

    @Autowired
    public AgentOrchestrationController(AiClassificationClient aiClassificationClient) {
        this.aiClassificationClient = aiClassificationClient;
    }

    @PostMapping("/orchestrate")
    public ResponseEntity<AgentOrchestrationResponseDto> orchestrate(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @RequestBody AgentOrchestrationRequestDto request,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);

        log.info("Received Agent Orchestration request for merchantId='{}', campaignId='{}'",
                effectiveMerchantId, request.campaignId());

        AgentOrchestrationRequestDto enrichedRequest = new AgentOrchestrationRequestDto(
                effectiveMerchantId.toString(),
                request.campaignId(),
                request.paymentId(),
                request.eventId(),
                request.failureCode(),
                request.failureReason(),
                request.amountInCents(),
                request.attemptCount(),
                request.backendUrl() != null ? request.backendUrl() : "http://localhost:8080"
        );

        AgentOrchestrationResponseDto response = aiClassificationClient.orchestrateAgent(enrichedRequest);
        return ResponseEntity.ok(response);
    }

    private UUID resolveMerchantId(String merchantHeader, AuthenticatedUser currentUser) {
        if (merchantHeader != null && !merchantHeader.isBlank()) {
            try {
                return UUID.fromString(merchantHeader.trim());
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("Invalid X-Merchant-Id header format");
            }
        }
        if (TenantContext.getCurrentMerchantId() != null) {
            return TenantContext.getCurrentMerchantId();
        }
        if (currentUser != null && currentUser.getMemberships() != null && !currentUser.getMemberships().isEmpty()) {
            return currentUser.getMemberships().get(0).getMerchantId();
        }
        throw new TenantAccessDeniedException("X-Merchant-Id header is required or no merchant association found for user");
    }
}
