package com.mend.integration;

import com.mend.controller.AnalyticsController;
import com.mend.controller.CampaignController;
import com.mend.controller.RecoveryActionController;
import com.mend.controller.WebhookController;
import com.mend.domain.entity.*;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.enums.WebhookEventStatus;
import com.mend.domain.repository.*;
import com.mend.dto.*;
import com.mend.exception.ResourceNotFoundException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.security.AuthenticatedUser;
import com.mend.security.UserPrincipalResolver;
import com.mend.service.AuthService;
import com.mend.service.CampaignLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class RestApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CampaignController campaignController;

    @Autowired
    private RecoveryActionController recoveryActionController;

    @Autowired
    private WebhookController webhookController;

    @Autowired
    private AnalyticsController analyticsController;

    @Autowired
    private AuthService authService;

    @Autowired
    private CampaignLifecycleService campaignLifecycleService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private CampaignAttemptRepository campaignAttemptRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @Autowired
    private UserPrincipalResolver userPrincipalResolver;

    private BootstrapResponse merchantA;
    private BootstrapResponse merchantB;
    private AuthenticatedUser userA;
    private AuthenticatedUser userB;
    private Campaign campaignA;
    private ActionIntent intentA;
    private WebhookEvent webhookA;

    @BeforeEach
    public void setUp() {
        merchantA = authService.bootstrap(new BootstrapRequest("Alpha Merchant", "admin@alpha.com", "Pass123!", "Alpha Admin"));
        merchantB = authService.bootstrap(new BootstrapRequest("Beta Merchant", "admin@beta.com", "Pass123!", "Beta Admin"));

        userA = userPrincipalResolver.resolveUser(merchantA.getUserId());
        userB = userPrincipalResolver.resolveUser(merchantB.getUserId());

        // Create campaign for merchant A
        campaignA = campaignLifecycleService.getOrCreateCampaign(merchantA.getMerchantId(), "pay_alpha_123", "cust_hash_1", "sub_1");
        campaignA.setFailureClass("INSUFFICIENT_FUNDS");
        campaignA.setConfidence(new BigDecimal("0.95"));
        campaignA.setStrategy("RETRY_LATER");
        campaignRepository.saveAndFlush(campaignA);

        // Classification result for campaign A
        ClassificationResult cr = new ClassificationResult(UUID.randomUUID(), campaignA.getId(), "INSUFFICIENT_FUNDS", new BigDecimal("0.95"));
        classificationResultRepository.saveAndFlush(cr);

        // Action intent for campaign A
        intentA = new ActionIntent(
                UUID.randomUUID(),
                merchantA.getMerchantId(),
                campaignA.getId(),
                1,
                "CHARGE",
                "RETRY_LATER",
                null,
                ActionIntentStatus.SCHEDULED,
                "intent-key-1",
                Instant.now()
        );
        actionIntentRepository.saveAndFlush(intentA);

        // Campaign attempt for campaign A
        CampaignAttempt attemptA = new CampaignAttempt(UUID.randomUUID(), campaignA.getId(), 1);
        attemptA.setActionType("CHARGE");
        attemptA.setStatus("SUCCESS");
        campaignAttemptRepository.saveAndFlush(attemptA);

        // Webhook event for merchant A
        webhookA = new WebhookEvent();
        webhookA.setId(UUID.randomUUID());
        webhookA.setExternalEventId("evt_alpha_1");
        webhookA.setEventType("payment.failed");
        webhookA.setSource("RAZORPAY");
        webhookA.setMerchantId(merchantA.getMerchantId());
        webhookA.setReceivedAt(Instant.now());
        webhookA.setProcessingStatus(WebhookEventStatus.VERIFIED);
        webhookEventRepository.saveAndFlush(webhookA);
    }

    // ==========================================
    // CAMPAIGN CONTROLLER TESTS
    // ==========================================

    @Test
    public void testGetCampaignsSuccess() {
        ResponseEntity<PageResponse<CampaignDto>> response = campaignController.getCampaigns(
                merchantA.getMerchantId().toString(), null, 0, 20, "createdAt", "desc", userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        PageResponse<CampaignDto> body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.getTotalElements());
        assertEquals(campaignA.getId(), body.getContent().get(0).getId());
        assertEquals("pay_alpha_123", body.getContent().get(0).getPaymentId());
    }

    @Test
    public void testGetCampaignByIdSuccess() {
        ResponseEntity<CampaignDto> response = campaignController.getCampaign(
                campaignA.getId(), merchantA.getMerchantId().toString(), userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(campaignA.getId(), response.getBody().getId());
        assertEquals("pay_alpha_123", response.getBody().getPaymentId());
    }

    @Test
    public void testGetCampaignByIdNotFound() {
        assertThrows(ResourceNotFoundException.class, () ->
                campaignController.getCampaign(UUID.randomUUID(), merchantA.getMerchantId().toString(), userA));
    }

    @Test
    public void testGetCampaignTimelineSuccess() {
        ResponseEntity<CampaignTimelineDto> response = campaignController.getCampaignTimeline(
                campaignA.getId(), merchantA.getMerchantId().toString(), userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        CampaignTimelineDto timeline = response.getBody();
        assertNotNull(timeline);
        assertEquals(campaignA.getId(), timeline.getCampaignId());
        assertNotNull(timeline.getClassification());
        assertEquals("INSUFFICIENT_FUNDS", timeline.getClassification().getFailureClass());
        assertEquals(1, timeline.getAttempts().size());
        assertEquals(1, timeline.getActionIntents().size());
    }

    @Test
    public void testCampaignCrossTenantAccessBlocked() {
        // User B trying to access Merchant A's campaign
        assertThrows(TenantAccessDeniedException.class, () ->
                campaignController.getCampaign(campaignA.getId(), merchantA.getMerchantId().toString(), userB));
    }

    // ==========================================
    // RECOVERY ACTION CONTROLLER TESTS
    // ==========================================

    @Test
    public void testGetRecoveryActionsSuccess() {
        ResponseEntity<PageResponse<ActionIntentDto>> response = recoveryActionController.getRecoveryActions(
                merchantA.getMerchantId().toString(), null, 0, 20, "createdAt", "desc", userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getTotalElements());
        assertEquals("CHARGE", response.getBody().getContent().get(0).getActionType());
    }

    @Test
    public void testGetRecoveryActionByIdSuccess() {
        ResponseEntity<ActionIntentDto> response = recoveryActionController.getRecoveryAction(
                intentA.getId(), merchantA.getMerchantId().toString(), userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(intentA.getId(), response.getBody().getId());
    }

    @Test
    public void testRecoveryActionCrossTenantAccessBlocked() {
        assertThrows(TenantAccessDeniedException.class, () ->
                recoveryActionController.getRecoveryAction(intentA.getId(), merchantA.getMerchantId().toString(), userB));
    }

    // ==========================================
    // WEBHOOK CONTROLLER TESTS
    // ==========================================

    @Test
    public void testGetWebhooksSuccess() {
        ResponseEntity<PageResponse<WebhookEventDetailDto>> response = webhookController.getWebhooks(
                merchantA.getMerchantId().toString(), null, 0, 20, "receivedAt", "desc", userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getTotalElements());
        assertEquals("evt_alpha_1", response.getBody().getContent().get(0).getExternalEventId());
    }

    @Test
    public void testGetWebhookByIdSuccess() {
        ResponseEntity<WebhookEventDetailDto> response = webhookController.getWebhook(
                webhookA.getId(), merchantA.getMerchantId().toString(), userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(webhookA.getId(), response.getBody().getId());
    }

    @Test
    public void testWebhookCrossTenantAccessBlocked() {
        assertThrows(TenantAccessDeniedException.class, () ->
                webhookController.getWebhook(webhookA.getId(), merchantA.getMerchantId().toString(), userB));
    }

    // ==========================================
    // ANALYTICS CONTROLLER TESTS
    // ==========================================

    @Test
    public void testGetAnalyticsOverviewSuccess() {
        ResponseEntity<AnalyticsOverviewDto> response = analyticsController.getOverview(
                merchantA.getMerchantId().toString(), userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AnalyticsOverviewDto overview = response.getBody();
        assertNotNull(overview);
        assertEquals(1, overview.getTotalCampaigns());
        assertEquals(1, overview.getTotalActionIntents());
        assertEquals(1, overview.getTotalAttempts());
    }

    @Test
    public void testGetAnalyticsRecoverySuccess() {
        ResponseEntity<AnalyticsRecoveryDto> response = analyticsController.getRecoveryAnalytics(
                merchantA.getMerchantId().toString(), userA
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AnalyticsRecoveryDto recovery = response.getBody();
        assertNotNull(recovery);
        assertTrue(recovery.getFailureClassBreakdown().containsKey("INSUFFICIENT_FUNDS"));
        assertEquals(1L, recovery.getFailureClassBreakdown().get("INSUFFICIENT_FUNDS"));
    }

    @Test
    public void testAnalyticsCrossTenantAccessBlocked() {
        assertThrows(TenantAccessDeniedException.class, () ->
                analyticsController.getOverview(merchantA.getMerchantId().toString(), userB));
    }
}
