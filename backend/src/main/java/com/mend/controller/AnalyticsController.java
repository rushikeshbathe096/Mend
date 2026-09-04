package com.mend.controller;

import com.mend.dto.AnalyticsOverviewDto;
import com.mend.dto.AnalyticsRecoveryDto;
import com.mend.exception.InvalidRequestException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.CurrentUser;
import com.mend.security.TenantContext;
import com.mend.service.AnalyticsQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsQueryService analyticsQueryService;

    public AnalyticsController(AnalyticsQueryService analyticsQueryService) {
        this.analyticsQueryService = analyticsQueryService;
    }

    @GetMapping("/overview")
    public ResponseEntity<AnalyticsOverviewDto> getOverview(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        AnalyticsOverviewDto overview = analyticsQueryService.getAnalyticsOverview(effectiveMerchantId, currentUser);
        return ResponseEntity.ok(overview);
    }

    @GetMapping("/recovery")
    public ResponseEntity<AnalyticsRecoveryDto> getRecoveryAnalytics(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        AnalyticsRecoveryDto recovery = analyticsQueryService.getAnalyticsRecovery(effectiveMerchantId, currentUser);
        return ResponseEntity.ok(recovery);
    }

    @GetMapping("/funnel")
    public ResponseEntity<com.mend.dto.AnalyticsFunnelDto> getFunnel(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        return ResponseEntity.ok(analyticsQueryService.getAnalyticsFunnel(effectiveMerchantId, currentUser));
    }

    @GetMapping("/failure-breakdown")
    public ResponseEntity<com.mend.dto.AnalyticsFailureBreakdownDto> getFailureBreakdown(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        return ResponseEntity.ok(analyticsQueryService.getAnalyticsFailureBreakdown(effectiveMerchantId, currentUser));
    }

    @GetMapping("/strategy-performance")
    public ResponseEntity<com.mend.dto.AnalyticsStrategyPerformanceDto> getStrategyPerformance(
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantHeader,
            @CurrentUser AuthenticatedUser currentUser) {

        UUID effectiveMerchantId = resolveMerchantId(merchantHeader, currentUser);
        return ResponseEntity.ok(analyticsQueryService.getAnalyticsStrategyPerformance(effectiveMerchantId, currentUser));
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
