package com.mend.controller;

import com.mend.dto.CustomerReplyDto;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.CustomerReplyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** M3 U2.4: customer replies (PII) and their reply-parsing suggestions; the contact opt-out. */
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}")
public class CustomerReplyController {

    private final CustomerReplyService replyService;

    public CustomerReplyController(CustomerReplyService replyService) {
        this.replyService = replyService;
    }

    public record SubmitReplyRequest(String text) {
    }

    @PostMapping("/replies")
    public ResponseEntity<CustomerReplyDto> submit(@PathVariable UUID campaignId, @RequestBody SubmitReplyRequest request,
                                                   @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
                                                   @CurrentUser AuthenticatedUser currentUser) {
        UUID merchantId = resolveMerchantId(merchantHeader, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(replyService.submit(merchantId, campaignId, request != null ? request.text() : null, currentUser));
    }

    @GetMapping("/replies")
    public ResponseEntity<List<CustomerReplyDto>> list(@PathVariable UUID campaignId,
                                                       @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
                                                       @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(replyService.list(resolveMerchantId(merchantHeader, currentUser), campaignId, currentUser));
    }

    @PostMapping("/replies/{replyId}/retry")
    public ResponseEntity<CustomerReplyDto> retry(@PathVariable UUID campaignId, @PathVariable UUID replyId,
                                                  @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
                                                  @CurrentUser AuthenticatedUser currentUser) {
        return ResponseEntity.ok(replyService.retry(resolveMerchantId(merchantHeader, currentUser), campaignId, replyId, currentUser));
    }

    @DeleteMapping("/opt-out")
    public ResponseEntity<Map<String, Boolean>> clearOptOut(@PathVariable UUID campaignId,
                                                            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
                                                            @CurrentUser AuthenticatedUser currentUser) {
        boolean cleared = replyService.clearOptOut(resolveMerchantId(merchantHeader, currentUser), campaignId, currentUser);
        return ResponseEntity.ok(Map.of("cleared", cleared));
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
