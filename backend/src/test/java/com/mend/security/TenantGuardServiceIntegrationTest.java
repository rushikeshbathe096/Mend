package com.mend.security;

import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.ReviewQueue;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.MerchantRepository;
import com.mend.dto.AddMerchantMemberRequest;
import com.mend.dto.UpdateMemberRoleRequest;
import com.mend.dto.UpdateMerchantConfigRequest;
import com.mend.exception.AuthenticationException;
import com.mend.exception.TenantAccessDeniedException;
import com.mend.integration.AbstractIntegrationTest;
import com.mend.service.*;
import com.mend.testsupport.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug sweep item 1: every user-facing service method goes through {@link TenantGuard#requireUserAccess}. For each one:
 * a user of another merchant and a missing user are denied (403), a member of the merchant is not. Internal (system) paths
 * that take no user keep working.
 */
class TenantGuardServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ActionIntentService actionIntentService;
    @Autowired private RecoveryStrategyService recoveryStrategyService;
    @Autowired private MerchantConfigService merchantConfigService;
    @Autowired private AuditService auditService;
    @Autowired private AnalyticsQueryService analyticsQueryService;
    @Autowired private ComplianceService complianceService;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private RecoveryActionQueryService recoveryActionQueryService;
    @Autowired private MerchantMemberService merchantMemberService;
    @Autowired private CampaignQueryService campaignQueryService;
    @Autowired private HumanApprovalService humanApprovalService;
    @Autowired private com.mend.service.CustomerReplyService customerReplyService;
    @Autowired private WebhookQueryService webhookQueryService;
    @Autowired private com.mend.service.ExperimentReportService experimentReportService;
    @Autowired private MerchantRepository merchantRepository;

    private UUID merchantId;

    /** One guarded method, called for {@code merchantId} on behalf of the given user. */
    private record GuardedCall(String method, Function<AuthenticatedUser, Object> call) {
    }

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        merchantRepository.save(new Merchant(merchantId, "Guard Merchant"));
    }

    private List<GuardedCall> guardedCalls() {
        UUID m = merchantId;
        UUID any = UUID.randomUUID();
        return List.of(
                new GuardedCall("ActionIntentService.getIntentHistory", u -> actionIntentService.getIntentHistory(m, any, u)),
                new GuardedCall("RecoveryStrategyService.getStrategyHistory", u -> recoveryStrategyService.getStrategyHistory(m, any, u)),
                new GuardedCall("MerchantConfigService.getMerchantConfig", u -> merchantConfigService.getMerchantConfig(m, u)),
                new GuardedCall("MerchantConfigService.updateMerchantConfig",
                        u -> merchantConfigService.updateMerchantConfig(m, new UpdateMerchantConfigRequest(), u)),
                new GuardedCall("AuditService.getCampaignAuditLogs", u -> auditService.getCampaignAuditLogs(m, any, u)),
                new GuardedCall("AuditService.getMerchantAuditLogs(pageable)",
                        u -> auditService.getMerchantAuditLogs(m, PageRequest.of(0, 5), u)),
                new GuardedCall("AuditService.getMerchantAuditLogs(filters)",
                        u -> auditService.getMerchantAuditLogs(m, null, null, null, PageRequest.of(0, 5), u)),
                new GuardedCall("AuditService.getMerchantAuditLogs(list)", u -> auditService.getMerchantAuditLogs(m, u)),
                new GuardedCall("AnalyticsQueryService.getAnalyticsOverview", u -> analyticsQueryService.getAnalyticsOverview(m, u)),
                new GuardedCall("AnalyticsQueryService.getAnalyticsRecovery", u -> analyticsQueryService.getAnalyticsRecovery(m, u)),
                new GuardedCall("AnalyticsQueryService.getAnalyticsFunnel", u -> analyticsQueryService.getAnalyticsFunnel(m, u)),
                new GuardedCall("AnalyticsQueryService.getAnalyticsFailureBreakdown",
                        u -> analyticsQueryService.getAnalyticsFailureBreakdown(m, u)),
                new GuardedCall("AnalyticsQueryService.getAnalyticsStrategyPerformance",
                        u -> analyticsQueryService.getAnalyticsStrategyPerformance(m, u)),
                new GuardedCall("ComplianceService.getComplianceHistory", u -> complianceService.getComplianceHistory(m, any, u)),
                new GuardedCall("CampaignLifecycleService.getCampaign", u -> campaignLifecycleService.getCampaign(m, any, u)),
                new GuardedCall("CampaignLifecycleService.getCampaignsForMerchant",
                        u -> campaignLifecycleService.getCampaignsForMerchant(m, u)),
                new GuardedCall("RecoveryActionQueryService.getRecoveryActions",
                        u -> recoveryActionQueryService.getRecoveryActions(m, null, 0, 5, "createdAt", "desc", u)),
                new GuardedCall("RecoveryActionQueryService.getRecoveryAction", u -> recoveryActionQueryService.getRecoveryAction(m, any, u)),
                new GuardedCall("MerchantMemberService.getMerchantMembers", u -> merchantMemberService.getMerchantMembers(m, u)),
                new GuardedCall("MerchantMemberService.addMerchantMember",
                        u -> merchantMemberService.addMerchantMember(m, new AddMerchantMemberRequest(), u)),
                new GuardedCall("MerchantMemberService.updateMemberRole",
                        u -> merchantMemberService.updateMemberRole(m, any, new UpdateMemberRoleRequest(), u)),
                new GuardedCall("MerchantMemberService.removeMerchantMember", u -> {
                    merchantMemberService.removeMerchantMember(m, any, u);
                    return null;
                }),
                new GuardedCall("CampaignQueryService.getCampaigns",
                        u -> campaignQueryService.getCampaigns(m, null, 0, 5, "createdAt", "desc", u)),
                new GuardedCall("CampaignQueryService.getCampaign", u -> campaignQueryService.getCampaign(m, any, u)),
                new GuardedCall("CampaignQueryService.getCampaignTimeline", u -> campaignQueryService.getCampaignTimeline(m, any, u)),
                new GuardedCall("HumanApprovalService.listReviews", u -> humanApprovalService.listReviews(m, null, 0, 5, u)),
                new GuardedCall("HumanApprovalService.getReview", u -> humanApprovalService.getReview(m, any, u)),
                new GuardedCall("HumanApprovalService.getReviewSummary", u -> humanApprovalService.getReviewSummary(m, u)),
                new GuardedCall("HumanApprovalService.approveReview", u -> humanApprovalService.approveReview(m, any, "ok", u)),
                new GuardedCall("HumanApprovalService.rejectReview", u -> humanApprovalService.rejectReview(m, any, "no", u)),
                // M4 U4.8: resolve-without-collecting and the experiment view are user-facing and tenant-guarded
                new GuardedCall("HumanApprovalService.resolveWithoutCollecting", u -> humanApprovalService.resolveWithoutCollecting(m, any, "no", u)),
                new GuardedCall("ExperimentReportService.forMerchant", u -> experimentReportService.forMerchant(m, u)),
                new GuardedCall("WebhookQueryService.getWebhooks",
                        u -> webhookQueryService.getWebhooks(m, null, 0, 5, "receivedAt", "desc", u)),
                new GuardedCall("WebhookQueryService.getWebhook", u -> webhookQueryService.getWebhook(m, any, u)),
                // M3 U2.4: customer replies are PII; every method is guarded and the campaign must be the merchant's
                new GuardedCall("CustomerReplyService.submit", u -> customerReplyService.submit(m, any, "reply", u)),
                new GuardedCall("CustomerReplyService.list", u -> customerReplyService.list(m, any, u)),
                new GuardedCall("CustomerReplyService.retry", u -> customerReplyService.retry(m, any, any, u)),
                new GuardedCall("CustomerReplyService.clearOptOut", u -> customerReplyService.clearOptOut(m, any, u))
        );
    }

    @TestFactory
    @DisplayName("A user of another merchant is denied by every guarded service method")
    Stream<DynamicTest> crossTenantUserIsDenied() {
        AuthenticatedUser otherTenantAdmin = TestUsers.memberOf(UUID.randomUUID());
        return guardedCalls().stream().map(c -> DynamicTest.dynamicTest(c.method(),
                () -> assertThrows(TenantAccessDeniedException.class, () -> c.call().apply(otherTenantAdmin))));
    }

    @TestFactory
    @DisplayName("No user is denied by every guarded service method (no null passes by accident)")
    Stream<DynamicTest> missingUserIsDenied() {
        // MerchantConfigService / MerchantMemberService answer a missing user with 401 (AuthenticationException) before the
        // guard; every other method gets the guard's 403. Either way the call is refused.
        return guardedCalls().stream().map(c -> DynamicTest.dynamicTest(c.method(), () -> {
            RuntimeException denied = assertThrows(RuntimeException.class, () -> c.call().apply(null));
            assertTrue(denied instanceof TenantAccessDeniedException || denied instanceof AuthenticationException,
                    c.method() + " threw " + denied);
        }));
    }

    @TestFactory
    @DisplayName("A member of the merchant passes the guard of every guarded service method")
    Stream<DynamicTest> memberPassesTheGuard() {
        AuthenticatedUser member = TestUsers.memberOf(merchantId);
        return guardedCalls().stream().map(c -> DynamicTest.dynamicTest(c.method(), () -> {
            try {
                c.call().apply(member);
            } catch (TenantAccessDeniedException e) {
                fail("member was denied by " + c.method() + ": " + e.getMessage());
            } catch (RuntimeException expected) {
                // not found / invalid request for the random ids: the call got past the guard
            }
        }));
    }

    @Test
    @DisplayName("The list above covers every public service method that takes an AuthenticatedUser")
    void everyUserFacingServiceMethodIsListed() throws Exception {
        Set<String> listed = guardedCalls().stream().map(c -> c.method().replaceAll("\\(.*\\)$", "")).collect(Collectors.toSet());
        Set<String> found = new TreeSet<>();
        for (Class<?> type : serviceClasses()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())
                        && Arrays.asList(method.getParameterTypes()).contains(AuthenticatedUser.class)) {
                    found.add(type.getSimpleName() + "." + method.getName());
                }
            }
        }
        found.remove("AuthService.getCurrentUser");   // returns the caller's own profile; not merchant-scoped
        assertEquals(found, new TreeSet<>(listed));
    }

    @Test
    @DisplayName("System calls (internal paths, no user) still work")
    void systemCallsStillWork() {
        TenantGuard.requireSystemAccess(merchantId, new TenantGuard.SystemCaller("TenantGuardServiceIntegrationTest"));

        Campaign campaign = campaignLifecycleService.getOrCreateCampaign(merchantId, "PAY-GUARD-" + UUID.randomUUID(), "CUST-G", null);
        campaign = campaignLifecycleService.transitionState(merchantId, campaign.getId(), CampaignStatus.CLASSIFIED,
                "Classified", "SYSTEM", null);
        assertEquals(CampaignStatus.CLASSIFIED, campaign.getCurrentState());
        ReviewQueue review = humanApprovalService.createReview(merchantId, campaign.getId(), "SYSTEM_TEST: system call");
        assertNotNull(review.getId());
        auditService.logEvent(merchantId, campaign.getId(), "SYSTEM_TEST", "SYSTEM", null, "system call");

        // ...and a member reads what the system wrote.
        AuthenticatedUser member = TestUsers.memberOf(merchantId);
        assertEquals(1, humanApprovalService.listReviews(merchantId, null, 0, 5, member).getTotalElements());
    }

    private static List<Class<?>> serviceClasses() throws Exception {
        String pkg = "com.mend.service";
        // The main classes directory (a classloader lookup of the package would find test-classes first).
        URL root = ActionIntentService.class.getProtectionDomain().getCodeSource().getLocation();
        List<Class<?>> types = new ArrayList<>();
        for (File file : Objects.requireNonNull(new File(new File(root.toURI()), pkg.replace('.', '/')).listFiles())) {
            String name = file.getName();
            if (name.endsWith(".class") && !name.contains("$")) {
                types.add(Class.forName(pkg + "." + name.substring(0, name.length() - ".class".length())));
            }
        }
        return types;
    }
}
