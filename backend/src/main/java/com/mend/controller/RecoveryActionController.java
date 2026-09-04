package com.mend.controller;

import com.mend.domain.enums.ActionIntentStatus;
import com.mend.dto.ActionIntentDto;
import com.mend.dto.PageResponse;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.RecoveryActionQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recovery/actions")
public class RecoveryActionController {

    private final RecoveryActionQueryService recoveryActionQueryService;

    public RecoveryActionController(RecoveryActionQueryService recoveryActionQueryService) {
        this.recoveryActionQueryService = recoveryActionQueryService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<ActionIntentDto>> getRecoveryActions(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @RequestParam(required = false) ActionIntentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        PageResponse<ActionIntentDto> response = recoveryActionQueryService.getRecoveryActions(
                effectiveMerchantId, status, page, size, sortBy, sortOrder, currentUser
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActionIntentDto> getRecoveryAction(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        ActionIntentDto action = recoveryActionQueryService.getRecoveryAction(effectiveMerchantId, id, currentUser);
        return ResponseEntity.ok(action);
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
