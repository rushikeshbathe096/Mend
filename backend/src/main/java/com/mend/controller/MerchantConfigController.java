package com.mend.controller;

import com.mend.dto.MerchantConfigDto;
import com.mend.dto.UpdateMerchantConfigRequest;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.MerchantConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/config")
public class MerchantConfigController {

    private final MerchantConfigService merchantConfigService;

    public MerchantConfigController(MerchantConfigService merchantConfigService) {
        this.merchantConfigService = merchantConfigService;
    }

    @GetMapping
    public ResponseEntity<MerchantConfigDto> getConfig(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        MerchantConfigDto config = merchantConfigService.getMerchantConfig(effectiveMerchantId, currentUser);
        return ResponseEntity.ok(config);
    }

    @PutMapping
    public ResponseEntity<MerchantConfigDto> updateConfig(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @RequestBody UpdateMerchantConfigRequest request,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        MerchantConfigDto config = merchantConfigService.updateMerchantConfig(effectiveMerchantId, request, currentUser);
        return ResponseEntity.ok(config);
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
