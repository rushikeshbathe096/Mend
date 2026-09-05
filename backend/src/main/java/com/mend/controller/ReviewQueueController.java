package com.mend.controller;

import com.mend.domain.enums.ReviewQueueStatus;
import com.mend.dto.PageResponse;
import com.mend.dto.ReviewDecisionRequest;
import com.mend.dto.ReviewDecisionResponse;
import com.mend.dto.ReviewItemDto;
import com.mend.dto.ReviewQueueSummaryDto;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.HumanApprovalService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Merchant human-approval queue.
 *
 * The frontend only ever submits approve/reject decisions. Provider execution
 * is never reachable from this controller: approval revalidates the tenant,
 * campaign state, compliance policy and ActionIntent state through
 * HumanApprovalService, then creates an ActionIntent via the authoritative
 * ActionIntentService boundary for the scheduler/executor to run.
 */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewQueueController {

    private final HumanApprovalService humanApprovalService;

    public ReviewQueueController(HumanApprovalService humanApprovalService) {
        this.humanApprovalService = humanApprovalService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<ReviewItemDto>> listReviews(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @RequestParam(required = false) ReviewQueueStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        Page<ReviewItemDto> reviews = humanApprovalService.listReviews(effectiveMerchantId, status, page, size, currentUser);
        return ResponseEntity.ok(PageResponse.of(
                reviews.getContent(), reviews.getNumber(), reviews.getSize(), reviews.getTotalElements()));
    }

    @GetMapping("/summary")
    public ResponseEntity<ReviewQueueSummaryDto> getReviewSummary(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        return ResponseEntity.ok(humanApprovalService.getReviewSummary(effectiveMerchantId, currentUser));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReviewItemDto> getReview(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        return ResponseEntity.ok(humanApprovalService.getReview(effectiveMerchantId, id, currentUser));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ReviewDecisionResponse> approveReview(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewDecisionRequest request,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        String comment = request != null ? request.getComment() : null;
        return ResponseEntity.ok(humanApprovalService.approveReview(effectiveMerchantId, id, comment, currentUser));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ReviewDecisionResponse> rejectReview(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewDecisionRequest request,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        String comment = request != null ? request.getComment() : null;
        return ResponseEntity.ok(humanApprovalService.rejectReview(effectiveMerchantId, id, comment, currentUser));
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
