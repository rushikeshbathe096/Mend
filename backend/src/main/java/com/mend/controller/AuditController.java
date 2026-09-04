package com.mend.controller;

import com.mend.domain.entity.AuditLog;
import com.mend.dto.AuditLogDto;
import com.mend.dto.PageResponse;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<AuditLogDto>> getAuditLogs(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sortBy", defaultValue = "createdAt") String sortBy,
            @RequestParam(value = "sortDir", defaultValue = "desc") String sortDir,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "actorType", required = false) String actorType,
            @RequestParam(value = "campaignId", required = false) UUID campaignId,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);

        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Page<AuditLog> auditPage = auditService.getMerchantAuditLogs(
                effectiveMerchantId, eventType, actorType, campaignId, pageRequest, currentUser
        );
        Page<AuditLogDto> dtoPage = auditPage.map(AuditLogDto::fromEntity);

        return ResponseEntity.ok(PageResponse.of(dtoPage));
    }

    @GetMapping("/campaign/{campaignId}")
    public ResponseEntity<List<AuditLogDto>> getCampaignAuditLogs(
            @PathVariable("campaignId") UUID campaignId,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        List<AuditLog> logs = auditService.getCampaignAuditLogs(effectiveMerchantId, campaignId, currentUser);
        List<AuditLogDto> dtos = logs.stream().map(AuditLogDto::fromEntity).toList();

        return ResponseEntity.ok(dtos);
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
