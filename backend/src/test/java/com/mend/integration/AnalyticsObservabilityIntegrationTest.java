package com.mend.integration;

import com.mend.controller.AnalyticsController;
import com.mend.controller.AuditController;
import com.mend.domain.entity.*;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.ComplianceStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.*;
import com.mend.dto.*;
import com.mend.security.AuthenticatedUser;
import com.mend.security.UserPrincipalResolver;
import com.mend.service.AuditService;
import com.mend.service.AuthService;
import com.mend.service.CampaignLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class AnalyticsObservabilityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AnalyticsController analyticsController;

    @Autowired
    private AuditController auditController;

    @Autowired
    private AuthService authService;

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private CampaignAttemptRepository campaignAttemptRepository;

    @Autowired
    private ComplianceDecisionRepository complianceDecisionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserPrincipalResolver userPrincipalResolver;

    private BootstrapResponse merchantA;
    private BootstrapResponse merchantB;
    private AuthenticatedUser userA;
    private AuthenticatedUser userB;
    private Campaign campaignA1;
    private Campaign campaignA2;

    @BeforeEach
    public void setUp() {
        MDC.put("correlationId", "test-corr-id-12345");

        merchantA = authService.bootstrap(new BootstrapRequest("Alpha Analytics Corp", "admin@analyticsa.com", "Pass123!", "Alpha Admin"));
        merchantB = authService.bootstrap(new BootstrapRequest("Beta Analytics Corp", "admin@analyticsb.com", "Pass123!", "Beta Admin"));

        userA = userPrincipalResolver.resolveUser(merchantA.getUserId());
        userB = userPrincipalResolver.resolveUser(merchantB.getUserId());

        // Merchant A - Campaign 1 (Recovered)
        campaignA1 = campaignLifecycleService.getOrCreateCampaign(merchantA.getMerchantId(), "pay_a1", "cust_a1", "sub_a1");
        campaignA1.setFailureClass("INSUFFICIENT_FUNDS");
        campaignA1.setConfidence(new BigDecimal("0.92"));
        campaignA1.setStrategy("RETRY_LATER");
        campaignA1.setCurrentState(CampaignStatus.RECOVERED);
        campaignRepository.saveAndFlush(campaignA1);

        // Merchant A - Campaign 2 (Active)
        campaignA2 = campaignLifecycleService.getOrCreateCampaign(merchantA.getMerchantId(), "pay_a2", "cust_a2", "sub_a2");
        campaignA2.setFailureClass("BANK_TECHNICAL_ERROR");
        campaignA2.setConfidence(new BigDecimal("0.75"));
        campaignA2.setStrategy("SMART_RETRY");
        campaignA2.setCurrentState(CampaignStatus.ACTION_PENDING);
        campaignRepository.saveAndFlush(campaignA2);

        // Action Intent for Merchant A
        ActionIntent intentA1 = new ActionIntent(
                UUID.randomUUID(), merchantA.getMerchantId(), campaignA1.getId(), 1,
                "CHARGE", "RETRY_LATER", null, ActionIntentStatus.SUCCEEDED, "key-a1", Instant.now()
        );
        actionIntentRepository.saveAndFlush(intentA1);

        // Campaign Attempt for Merchant A
        CampaignAttempt attemptA1 = new CampaignAttempt(UUID.randomUUID(), campaignA1.getId(), 1);
        attemptA1.setActionType("CHARGE");
        attemptA1.setStatus("SUCCESS");
        campaignAttemptRepository.saveAndFlush(attemptA1);

        // Compliance Decision for Merchant A
        ComplianceDecisionEntity complianceA1 = new ComplianceDecisionEntity(
                UUID.randomUUID(), campaignA1.getId(), merchantA.getMerchantId(), null,
                com.mend.domain.enums.RecoveryStrategy.RETRY_LATER,
                ComplianceStatus.COMPLIANCE_ALLOWED,
                com.mend.domain.enums.ComplianceReason.ALLOWED,
                "Compliance passed", "v1.0"
        );
        complianceDecisionRepository.saveAndFlush(complianceA1);

        ComplianceDecisionEntity complianceA2 = new ComplianceDecisionEntity(
                UUID.randomUUID(), campaignA2.getId(), merchantA.getMerchantId(), null,
                com.mend.domain.enums.RecoveryStrategy.RETRY_IMMEDIATELY,
                ComplianceStatus.COMPLIANCE_BLOCKED,
                com.mend.domain.enums.ComplianceReason.MAX_ATTEMPTS_EXCEEDED,
                "Rate limit exceeded", "v1.0"
        );
        complianceDecisionRepository.saveAndFlush(complianceA2);

        // Webhook Event for Merchant A with raw payload
        WebhookEvent webhookA1 = new WebhookEvent();
        webhookA1.setId(UUID.randomUUID());
        webhookA1.setExternalEventId("evt_a1");
        webhookA1.setEventType("payment.failed");
        webhookA1.setSource("RAZORPAY");
        webhookA1.setMerchantId(merchantA.getMerchantId());
        webhookA1.setReceivedAt(Instant.now());
        webhookA1.setProcessedAt(Instant.now().plusMillis(250));
        webhookA1.setProcessingStatus(WebhookEventStatus.PROCESSED);
        webhookA1.setRawPayload("{\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_a1\",\"amount\":150000,\"status\":\"failed\"}}}}");
        webhookEventRepository.saveAndFlush(webhookA1);

        // Log structured audit events
        auditService.logStructuredEvent(
                merchantA.getMerchantId(), campaignA1.getId(),
                "CAMPAIGN_STATE_TRANSITION", "SYSTEM", null,
                "State changed to RECOVERED with key secret_key_rzp_test_12345",
                java.util.Map.of("previousState", "EXECUTING", "newState", "RECOVERED", "secret_key", "rzp_test_999"),
                java.util.Map.of("evidenceRef", "evt_a1")
        );
    }

    @Test
    public void testAnalyticsOverviewMetrics() {
        ResponseEntity<AnalyticsOverviewDto> response = analyticsController.getOverview(
                merchantA.getMerchantId().toString(), userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AnalyticsOverviewDto dto = response.getBody();
        assertNotNull(dto);

        assertEquals(2, dto.getTotalCampaigns());
        assertEquals(1, dto.getRecoveredCampaigns());
        assertEquals(1, dto.getActiveCampaigns());
        assertEquals(50.0, dto.getRecoveryRate());
        assertEquals(1, dto.getComplianceBlocks());
        assertTrue(dto.getRevenueAtRisk() > 0.0);
    }

    @Test
    public void testAnalyticsRecoveryBreakdownMetrics() {
        ResponseEntity<AnalyticsRecoveryDto> response = analyticsController.getRecoveryAnalytics(
                merchantA.getMerchantId().toString(), userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AnalyticsRecoveryDto dto = response.getBody();
        assertNotNull(dto);

        assertTrue(dto.getFailureClassBreakdown().containsKey("INSUFFICIENT_FUNDS"));
        assertTrue(dto.getFailureClassBreakdown().containsKey("BANK_TECHNICAL_ERROR"));
        assertNotNull(dto.getAiConfidenceMetrics());
        assertTrue(dto.getAiConfidenceMetrics().containsKey("averageConfidence"));
        assertNotNull(dto.getComplianceMetrics());
        assertEquals(1L, dto.getComplianceMetrics().get("blockedCount"));
        assertEquals(1L, dto.getComplianceMetrics().get("allowedCount"));
    }

    @Test
    public void testMerchantAuditLogsQueryAndSanitization() {
        ResponseEntity<PageResponse<AuditLogDto>> response = auditController.getAuditLogs(
                merchantA.getMerchantId().toString(), 0, 20, "createdAt", "desc", null, null, null, userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        PageResponse<AuditLogDto> page = response.getBody();
        assertNotNull(page);
        assertTrue(page.getTotalElements() > 0);

        AuditLogDto logDto = page.getContent().get(0);
        assertNotNull(logDto.getEventType());

        // Verify secret sanitization in audit logs
        for (AuditLogDto item : page.getContent()) {
            if (item.getReason() != null) {
                assertFalse(item.getReason().contains("rzp_test_12345"), "Secrets must be redacted in reason string!");
            }
            if (item.getMetadata() != null && item.getMetadata().containsKey("secret_key")) {
                assertEquals("[REDACTED]", item.getMetadata().get("secret_key"), "Sensitive metadata keys must be redacted!");
            }
        }
    }

    @Test
    public void testCampaignAuditLogsEndpoint() {
        ResponseEntity<List<AuditLogDto>> response = auditController.getCampaignAuditLogs(
                campaignA1.getId(), merchantA.getMerchantId().toString(), userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<AuditLogDto> logs = response.getBody();
        assertNotNull(logs);
        assertFalse(logs.isEmpty());
        assertEquals(campaignA1.getId(), logs.get(0).getCampaignId());
    }

    @Test
    public void testMultiTenantIsolationInAnalyticsAndAudit() {
        // Merchant B should not see Merchant A's audit logs or analytics
        ResponseEntity<AnalyticsOverviewDto> overviewB = analyticsController.getOverview(
                merchantB.getMerchantId().toString(), userB
        );
        assertEquals(HttpStatus.OK, overviewB.getStatusCode());
        assertEquals(0, overviewB.getBody().getTotalCampaigns());

        // User B accessing Merchant A's endpoints must be denied
        assertThrows(Exception.class, () ->
                analyticsController.getOverview(merchantA.getMerchantId().toString(), userB));

        assertThrows(Exception.class, () ->
                auditController.getAuditLogs(merchantA.getMerchantId().toString(), 0, 20, "createdAt", "desc", null, null, null, userB));
    }
}
