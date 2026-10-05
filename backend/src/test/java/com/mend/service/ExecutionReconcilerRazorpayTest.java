package com.mend.service;

import com.mend.domain.entity.ActionIntent;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.enums.ActionType;
import com.mend.domain.enums.CampaignStatus;
import com.mend.domain.repository.*;
import com.mend.provider.PaymentProvider;
import com.mend.provider.ProviderAttemptKey;
import com.mend.provider.razorpay.RazorpayStubServer;
import com.mend.statemachine.CampaignStateMachine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 U5: §7 decision-table rows in Razorpay mode. The real Razorpay adapter talks to a local stub replaying RECORDED
 * fixtures 05/06/20/21 and the doc-derived empty reference lookup. The fixtures' {@code reference_id} is replaced by the
 * intent's attempt key (the adapter checks it); nothing else is changed. The adapter is built by hand (as in
 * RazorpayTestModeEndToEndIntegrationTest) so this test reuses the shared test context instead of adding another one.
 */
@DisplayName("M2 U5: ExecutionReconciler against recorded Razorpay fixtures")
class ExecutionReconcilerRazorpayTest extends com.mend.integration.AbstractIntegrationTest {

    static final String SINGLE_TENANT_MERCHANT = "7b1e2c3d-4f5a-4b6c-8d7e-9f0a1b2c3d4e";
    private static final String RECORDED_LINK_ID = "plink_Tk6TAI6WoeOTDh";
    private static final String RECORDED_PAYMENT_ID = "pay_Tk6YGMVnG6oWIq";
    private static final String RECORDED_REFERENCE = "mnd_rec_1791176340";

    private static final RazorpayStubServer RAZORPAY_STUB = RazorpayStubServer.start();

    @AfterAll
    static void stopStub() {
        RAZORPAY_STUB.close();
    }

    @Autowired private ActionIntentRepository intents;
    @Autowired private ActionIntentTransitions transitions;
    @Autowired private CampaignRepository campaigns;
    @Autowired private CampaignAttemptRepository attempts;
    @Autowired private CampaignLifecycleService campaignLifecycleService;
    @Autowired private CampaignStateMachine campaignStateMachine;
    @Autowired private HumanApprovalService humanApprovalService;
    @Autowired private AuditService auditService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MerchantRepository merchants;
    @Autowired private JdbcTemplate jdbc;

    private final UUID merchantId = UUID.fromString(SINGLE_TENANT_MERCHANT);
    private ExecutionReconciler reconciler;

    @BeforeEach
    void setUp() {
        RAZORPAY_STUB.reset();
        if (!merchants.existsById(merchantId)) {
            merchants.saveAndFlush(new Merchant(merchantId, "M2 U5 Razorpay merchant"));
        }
        jdbc.update("UPDATE action_intents SET reconcile_after = now() + interval '30 days' "
                + "WHERE status IN ('NEEDS_RECONCILIATION', 'AWAITING_PAYMENT')");
        com.mend.provider.razorpay.RazorpayProperties properties = new com.mend.provider.razorpay.RazorpayProperties(
                new com.mend.provider.razorpay.RazorpayProperties.Api(RAZORPAY_STUB.baseUrl()), "rzp_test_key_m2u5", "rzp_test_secret_m2u5",
                1000, 1000, SINGLE_TENANT_MERCHANT);
        tools.jackson.databind.ObjectMapper providerMapper = new tools.jackson.databind.ObjectMapper();
        PaymentProvider razorpay = new com.mend.provider.razorpay.RazorpayPaymentProvider(
                new com.mend.provider.razorpay.RazorpayHttp(properties, providerMapper),
                new com.mend.provider.razorpay.SingleTenantEnvCredentialsResolver(properties), providerMapper);
        reconciler = new ExecutionReconciler(intents, transitions, razorpay, new com.mend.provider.razorpay.RazorpayProviderCapabilities(), campaigns, attempts, campaignLifecycleService,
                campaignStateMachine, humanApprovalService, auditService, transactionManager,
                new ExecutionReconciler.Settings(true, false, 50, Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(120), 3, 5),
                new ReconcileBackoff(Duration.ofSeconds(30), Duration.ofHours(6), new SplittableRandom(5)), Clock.systemUTC());
    }

    /** A dispatched link intent of a campaign in EXECUTING, created directly in the given reconcile state and due now. */
    private ActionIntent intentIn(ActionIntentStatus status, String responseReference, Instant dispatchedAt) {
        Campaign campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_m2u5rzp" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        campaign.setCurrentState(CampaignStatus.EXECUTING);
        campaign = campaigns.save(campaign);
        ActionIntent intent = new ActionIntent(UUID.randomUUID(), merchantId, campaign.getId(), 1, ActionType.REQUEST_CUSTOMER_ACTION.name(),
                "CUSTOMER_ACTION_REQUIRED", null, status, "intent:" + campaign.getId() + ":attempt_1:REQUEST_CUSTOMER_ACTION",
                Instant.now().minusSeconds(600));
        intent.setResponseReference(responseReference);
        intent.setDispatchedAt(dispatchedAt);
        intent.setExecutionAttempts(1);
        intent.setReconcileAfter(Instant.now().minusSeconds(1));
        return intents.save(intent);
    }

    private static String fixtureBody(String folder, String fixture, ProviderAttemptKey key) throws Exception {
        try (var in = ExecutionReconcilerRazorpayTest.class.getResourceAsStream("/razorpay/" + folder + "/" + fixture + ".json")) {
            var mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(mapper.readTree(in).path("response").path("body")).replace(RECORDED_REFERENCE, key.value());
        }
    }

    private ActionIntent reload(ActionIntent intent) {
        return intents.findById(intent.getId()).orElseThrow();
    }

    private void assertOnlyReads() {
        assertTrue(RAZORPAY_STUB.requests().stream().allMatch(request -> request.method().equals("GET")),
                "the reconciler must never call a create/POST operation: " + RAZORPAY_STUB.requests());
        assertFalse(RAZORPAY_STUB.requests().isEmpty());
    }

    @Test
    @DisplayName("NR + reference lookup finds a created link (fixture 06) -> AWAITING_PAYMENT with the plink id")
    void needsReconciliationFoundCreatedLink() throws Exception {
        ActionIntent intent = intentIn(ActionIntentStatus.NEEDS_RECONCILIATION, null, Instant.now().minusSeconds(30));
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        RAZORPAY_STUB.stub("GET", "/v1/payment_links?reference_id=" + key.value(), 200, fixtureBody("recorded", "06_fetch_link_by_reference", key));

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, reload(intent).getStatus());
        assertEquals(RECORDED_LINK_ID, reload(intent).getResponseReference());
        assertOnlyReads();
    }

    @Test
    @DisplayName("NR + empty reference lookup (doc-derived) within grace -> stays; after grace -> SAFE_RETRY to READY")
    void needsReconciliationEmptyLookup() throws Exception {
        ActionIntent recent = intentIn(ActionIntentStatus.NEEDS_RECONCILIATION, null, Instant.now().minusSeconds(30));
        ActionIntent old = intentIn(ActionIntentStatus.NEEDS_RECONCILIATION, null, Instant.now().minusSeconds(600));
        for (ActionIntent intent : new ActionIntent[]{recent, old}) {
            ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
            RAZORPAY_STUB.stub("GET", "/v1/payment_links?reference_id=" + key.value(), 200, fixtureBody("doc-derived", "fetch_links_empty", key));
        }

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.NEEDS_RECONCILIATION, reload(recent).getStatus());
        assertTrue(reload(recent).getReconcileAfter().isAfter(Instant.now()));
        assertEquals(ActionIntentStatus.READY, reload(old).getStatus());
        assertEquals(CampaignStatus.ACTION_PENDING, campaigns.findById(old.getCampaignId()).orElseThrow().getCurrentState());
        assertOnlyReads();
    }

    @Test
    @DisplayName("AWAITING_PAYMENT + link still created (fixture 05) -> stays, backoff")
    void awaitingPaymentLinkStillCreated() throws Exception {
        ActionIntent intent = intentIn(ActionIntentStatus.AWAITING_PAYMENT, RECORDED_LINK_ID, Instant.now().minusSeconds(600));
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        RAZORPAY_STUB.stub("GET", "/v1/payment_links/" + RECORDED_LINK_ID, 200, fixtureBody("recorded", "05_fetch_link_by_id", key));

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, reload(intent).getStatus());
        assertEquals(1, reload(intent).getReconcileAttempts());
        assertTrue(reload(intent).getReconcileAfter().isAfter(Instant.now()));
        assertOnlyReads();
    }

    @Test
    @DisplayName("AWAITING_PAYMENT + paid link (fixture 20) whose payment is captured (fixture 21) -> SUCCEEDED, campaign RECOVERED")
    void awaitingPaymentPaidAndCaptured() throws Exception {
        ActionIntent intent = intentIn(ActionIntentStatus.AWAITING_PAYMENT, RECORDED_LINK_ID, Instant.now().minusSeconds(600));
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        RAZORPAY_STUB.stub("GET", "/v1/payment_links/" + RECORDED_LINK_ID, 200, fixtureBody("recorded", "20_fetch_link_paid", key));
        RAZORPAY_STUB.stub("GET", "/v1/payments/" + RECORDED_PAYMENT_ID, 200, fixtureBody("recorded", "21_fetch_payment_captured", key));

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.SUCCEEDED, reload(intent).getStatus());
        assertEquals(RECORDED_PAYMENT_ID, reload(intent).getResponseReference());
        assertEquals(CampaignStatus.RECOVERED, campaigns.findById(intent.getCampaignId()).orElseThrow().getCurrentState());
        assertOnlyReads();
    }

    @Test
    @DisplayName("AWAITING_PAYMENT + paid link but the payment lookup fails -> stays (a paid link alone is not money)")
    void awaitingPaymentPaidWithoutPaymentEvidence() throws Exception {
        ActionIntent intent = intentIn(ActionIntentStatus.AWAITING_PAYMENT, RECORDED_LINK_ID, Instant.now().minusSeconds(600));
        ProviderAttemptKey key = ProviderAttemptKey.forIntent(intent.getId());
        RAZORPAY_STUB.stub("GET", "/v1/payment_links/" + RECORDED_LINK_ID, 200, fixtureBody("recorded", "20_fetch_link_paid", key));
        // no stub for /v1/payments/{id}: the stub answers 599 (Unavailable)

        reconciler.runCycle();

        assertEquals(ActionIntentStatus.AWAITING_PAYMENT, reload(intent).getStatus());
        assertEquals(CampaignStatus.EXECUTING, campaigns.findById(intent.getCampaignId()).orElseThrow().getCurrentState());
        assertOnlyReads();
    }
}
