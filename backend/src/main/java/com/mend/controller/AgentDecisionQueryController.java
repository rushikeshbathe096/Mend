package com.mend.controller;

import com.mend.domain.entity.AgentDecisionRecord;
import com.mend.domain.repository.AgentDecisionRecordRepository;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AgentDecisionQueryController {

    private final AgentDecisionRecordRepository agentDecisionRecordRepository;

    public AgentDecisionQueryController(AgentDecisionRecordRepository agentDecisionRecordRepository) {
        this.agentDecisionRecordRepository = agentDecisionRecordRepository;
    }

    @GetMapping("/campaigns/{campaignId}/decisions")
    public ResponseEntity<List<AgentDecisionRecord>> getCampaignDecisions(
            @PathVariable UUID campaignId,
            @RequestHeader(value = "X-Merchant-Id", required = false) String headerMerchantId,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID merchantId = TenantContext.getCurrentMerchantId();
        if (merchantId == null && headerMerchantId != null && !headerMerchantId.isBlank()) {
            merchantId = UUID.fromString(headerMerchantId);
        }

        List<AgentDecisionRecord> records = agentDecisionRecordRepository.findByCampaignIdOrderByCreatedAtDesc(campaignId);
        return ResponseEntity.ok(records);
    }

    @GetMapping("/agent/timeline")
    public ResponseEntity<List<AgentDecisionRecord>> getMerchantAgentTimeline(
            @RequestParam(required = false) UUID merchantId,
            @RequestHeader(value = "X-Merchant-Id", required = false) String headerMerchantId,
            @CurrentUser AuthenticatedUser currentUser) {

        // The merchantId query param bypasses SecurityFilter's X-Merchant-Id membership check.
        if (merchantId != null && (currentUser == null || !currentUser.isMemberOfMerchant(merchantId))) {
            throw new TenantAccessDeniedException("Access denied for merchant: " + merchantId);
        }

        UUID effectiveMerchantId = merchantId;
        if (effectiveMerchantId == null) {
            effectiveMerchantId = TenantContext.getCurrentMerchantId();
        }

        if (effectiveMerchantId == null && headerMerchantId != null && !headerMerchantId.isBlank()) {
            effectiveMerchantId = UUID.fromString(headerMerchantId);
        }

        if (effectiveMerchantId == null) {
            return ResponseEntity.badRequest().build();
        }

        List<AgentDecisionRecord> records = agentDecisionRecordRepository.findByMerchantIdOrderByCreatedAtDesc(effectiveMerchantId);
        return ResponseEntity.ok(records);
    }
}
