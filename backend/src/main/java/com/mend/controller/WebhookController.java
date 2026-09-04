package com.mend.controller;

import com.mend.domain.enums.WebhookEventStatus;
import com.mend.dto.PageResponse;
import com.mend.dto.WebhookEventDetailDto;
import com.mend.dto.WebhookResponseDto;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.WebhookQueryService;
import com.mend.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final WebhookService webhookService;
    private final WebhookQueryService webhookQueryService;

    public WebhookController(WebhookService webhookService, WebhookQueryService webhookQueryService) {
        this.webhookService = webhookService;
        this.webhookQueryService = webhookQueryService;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<WebhookResponseDto> handleRazorpayWebhook(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestBody(required = false) String rawPayload) {

        WebhookResponseDto response = webhookService.processRazorpayWebhook(rawPayload, signature);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<WebhookEventDetailDto>> getWebhooks(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @RequestParam(required = false) WebhookEventStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "receivedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        PageResponse<WebhookEventDetailDto> response = webhookQueryService.getWebhooks(
                effectiveMerchantId, status, page, size, sortBy, sortOrder, currentUser
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WebhookEventDetailDto> getWebhook(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        WebhookEventDetailDto event = webhookQueryService.getWebhook(effectiveMerchantId, id, currentUser);
        return ResponseEntity.ok(event);
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
