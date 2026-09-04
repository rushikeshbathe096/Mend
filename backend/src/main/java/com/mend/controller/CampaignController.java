package com.mend.controller;

import com.mend.domain.enums.CampaignStatus;
import com.mend.dto.CampaignDto;
import com.mend.dto.CampaignTimelineDto;
import com.mend.dto.PageResponse;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.CampaignQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {

    private final CampaignQueryService campaignQueryService;

    public CampaignController(CampaignQueryService campaignQueryService) {
        this.campaignQueryService = campaignQueryService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<CampaignDto>> getCampaigns(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        PageResponse<CampaignDto> response = campaignQueryService.getCampaigns(
                effectiveMerchantId, status, page, size, sortBy, sortOrder, currentUser
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CampaignDto> getCampaign(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        CampaignDto campaign = campaignQueryService.getCampaign(effectiveMerchantId, id, currentUser);
        return ResponseEntity.ok(campaign);
    }

    @GetMapping("/{id}/timeline")
    public ResponseEntity<CampaignTimelineDto> getCampaignTimeline(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        CampaignTimelineDto timeline = campaignQueryService.getCampaignTimeline(effectiveMerchantId, id, currentUser);
        return ResponseEntity.ok(timeline);
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
