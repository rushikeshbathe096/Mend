package com.mend.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mend.domain.entity.ActionIntent;
import com.mend.domain.enums.ActionIntentStatus;
import com.mend.domain.repository.ActionIntentRepository;
import com.mend.dto.AddMerchantMemberRequest;
import com.mend.dto.BootstrapRequest;
import com.mend.dto.BootstrapResponse;
import com.mend.dto.MerchantMemberDto;
import com.mend.security.JwtService;
import com.mend.security.SecurityFilter;
import com.mend.security.UserPrincipalResolver;
import com.mend.service.ActionExecutionService;
import com.mend.service.AuthService;
import com.mend.service.MerchantMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 19/20 - Merchant product workflow verification.
 *
 * Covers the human-approval queue (list/approve/reject with backend
 * revalidation), deterministic demo scenarios running through the real
 * recovery services, merchant endpoints (payments/customers), and strict
 * tenant isolation for review items.
 */
@Transactional
public class Phase19MerchantProductIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private SecurityFilter securityFilter;

    @Autowired
    private AuthService authService;

    @Autowired
    private MerchantMemberService merchantMemberService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserPrincipalResolver userPrincipalResolver;

    @Autowired
    private ActionExecutionService actionExecutionService;

    @Autowired
    private ActionIntentRepository actionIntentRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    private BootstrapResponse merchantA;
    private BootstrapResponse merchantB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(securityFilter)
                .build();

        String salt = UUID.randomUUID().toString().substring(0, 8);
        merchantA = authService.bootstrap(new BootstrapRequest("Alpha Corp", "p19-alpha-" + salt + "@mend.test", "Pass123!", "Alpha Admin"));
        merchantB = authService.bootstrap(new BootstrapRequest("Beta Corp", "p19-beta-" + salt + "@mend.test", "Pass123!", "Beta Admin"));

        tokenA = jwtService.generateToken(merchantA.getUserId(), merchantA.getUserEmail(), List.of("MERCHANT_ADMIN"));
        tokenB = jwtService.generateToken(merchantB.getUserId(), merchantB.getUserEmail(), List.of("MERCHANT_ADMIN"));
    }

    private MvcResult runScenario(String token, UUID merchantId, String scenario) throws Exception {
        return mockMvc.perform(post("/api/v1/demo/trigger-scenario")
                .header("Authorization", "Bearer " + token)
                .header("X-Merchant-Id", merchantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"" + scenario + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", org.hamcrest.Matchers.equalTo("SUCCESS")))
                .andReturn();
    }

    private JsonNode runScenarioJson(String token, UUID merchantId, String scenario) throws Exception {
        MvcResult result = runScenario(token, merchantId, scenario);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // Demo scenario catalog + scenario 1 (low-risk automated retry)
    // ------------------------------------------------------------------

    @Test
    public void demoScenarioCatalogIsAvailable() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/demo/scenarios")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(root.isArray());
        org.junit.jupiter.api.Assertions.assertEquals(6, root.size());
    }

    @Test
    public void lowRiskRetryScenarioRecoversThroughRealExecutionPath() throws Exception {
        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "LOW_RISK_RETRY");

        String campaignId = response.get("campaignId").asText();
        String paymentId = response.get("paymentId").asText();
        org.junit.jupiter.api.Assertions.assertEquals("RECOVERED", response.get("finalCampaignState").asText());

        // Payment detail is served from authoritative campaign + webhook data (no hardcoded amounts)
        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentSummary.failureClass").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.paymentSummary.amount").value(4999.0))
                .andExpect(jsonPath("$.paymentSummary.currentState").value("RECOVERED"))
                .andExpect(jsonPath("$.campaign.currentState").value("RECOVERED"));

        // Campaign list search returns exactly the demo campaign
        MvcResult listResult = mockMvc.perform(get("/api/v1/payments")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .param("search", paymentId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(1, list.get("totalElements").asLong());
    }

    // ------------------------------------------------------------------
    // Scenario 2 - high-risk human review, then approval -> execution
    // ------------------------------------------------------------------

    @Test
    public void highRiskReviewThenApprovalRevalidatesAndRecovers() throws Exception {
        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "HIGH_RISK_HUMAN_REVIEW");

        String reviewId = response.get("reviewId").asText();
        String campaignId = response.get("campaignId").asText();
        org.junit.jupiter.api.Assertions.assertEquals("ELIGIBLE", response.get("finalCampaignState").asText());

        // Review is visible in the merchant approval queue as PENDING
        mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.campaignState").value("ELIGIBLE"));

        // Approve: backend revalidation creates the ActionIntent through the compliance boundary
        MvcResult approveResult = mockMvc.perform(post("/api/v1/reviews/" + reviewId + "/approve")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Approved during audit verification\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andReturn();

        JsonNode approveJson = objectMapper.readTree(approveResult.getResponse().getContentAsString());
        String intentId = approveJson.get("actionIntent").get("id").asText();

        // Simulate the scheduler claim + execute through the real executor boundary
        ActionIntent intent = actionIntentRepository.findById(UUID.fromString(intentId)).orElseThrow();
        intent.setStatus(ActionIntentStatus.CLAIMED);
        intent.setWorkerId("phase19-test-worker");
        intent.setClaimedAt(Instant.now());
        actionIntentRepository.saveAndFlush(intent);
        actionExecutionService.executeActionIntent(intent.getId(), "phase19-test-worker");

        // Campaign reached RECOVERED with authoritative evidence (provider execution result)
        mockMvc.perform(get("/api/v1/campaigns/" + campaignId)
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentState").value("RECOVERED"));

        // Review summary reflects the decision
        MvcResult summary = mockMvc.perform(get("/api/v1/reviews/summary")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode summaryJson = objectMapper.readTree(summary.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(0, summaryJson.get("pending").asLong());
        org.junit.jupiter.api.Assertions.assertTrue(summaryJson.get("byStatus").get("APPROVED").asLong() >= 1);
    }

    @Test
    public void highRiskReviewThenRejectionCancelsCampaign() throws Exception {
        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "HIGH_RISK_HUMAN_REVIEW");
        String reviewId = response.get("reviewId").asText();
        String campaignId = response.get("campaignId").asText();

        mockMvc.perform(post("/api/v1/reviews/" + reviewId + "/reject")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Rejected by policy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECTED"));

        mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/v1/campaigns/" + campaignId)
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentState").value("CANCELLED"));
    }

    @Test
    public void conflictingSecondDecisionOnResolvedReviewIsRejected() throws Exception {
        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "HIGH_RISK_HUMAN_REVIEW");
        String reviewId = response.get("reviewId").asText();

        // First decision succeeds
        mockMvc.perform(post("/api/v1/reviews/" + reviewId + "/approve")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"first decision\"}"))
                .andExpect(status().isOk());

        // A second, conflicting decision must fail with a clear conflict - not a 500
        mockMvc.perform(post("/api/v1/reviews/" + reviewId + "/reject")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"conflicting second decision\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("already been resolved")));
    }

    // ------------------------------------------------------------------
    // Scenario 3 - customer action with reconciliation
    // ------------------------------------------------------------------

    @Test
    public void customerActionScenarioReconcilesToRecovered() throws Exception {
        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "CUSTOMER_ACTION");
        org.junit.jupiter.api.Assertions.assertEquals("RECOVERED", response.get("finalCampaignState").asText());
        org.junit.jupiter.api.Assertions.assertNotNull(response.get("campaignId"));
    }

    @Test
    public void providerAmbiguityScenarioReconcilesFromEvidence() throws Exception {
        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "PROVIDER_AMBIGUITY");
        org.junit.jupiter.api.Assertions.assertEquals("RECOVERED", response.get("finalCampaignState").asText());
    }

    @Test
    public void duplicateEventScenarioInterceptsSecondDelivery() throws Exception {
        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "DUPLICATE_EVENT");
        org.junit.jupiter.api.Assertions.assertEquals("SUCCESS", response.get("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(response.get("message").asText().contains("deduplication"));
    }

    @Test
    public void agentFailureScenarioFallsBackSafely() throws Exception {
        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "AGENT_FAILURE");
        org.junit.jupiter.api.Assertions.assertEquals("EXHAUSTED", response.get("finalCampaignState").asText());
    }

    // ------------------------------------------------------------------
    // Tenant isolation for reviews, payments and customers
    // ------------------------------------------------------------------

    @Test
    public void merchantBCannotSeeOrDecideMerchantAReviews() throws Exception {
        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "HIGH_RISK_HUMAN_REVIEW");
        String reviewId = response.get("reviewId").asText();
        String paymentId = response.get("paymentId").asText();

        // Merchant B's review list does not expose Merchant A's review
        MvcResult list = mockMvc.perform(get("/api/v1/reviews")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Merchant-Id", merchantB.getMerchantId().toString())
                .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listJson = objectMapper.readTree(list.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(0, listJson.get("totalElements").asLong());

        // Merchant B cannot read the review item by ID
        mockMvc.perform(get("/api/v1/reviews/" + reviewId)
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Merchant-Id", merchantB.getMerchantId().toString()))
                .andExpect(status().isNotFound());

        // Merchant B cannot approve it
        mockMvc.perform(post("/api/v1/reviews/" + reviewId + "/approve")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Merchant-Id", merchantB.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"cross tenant attempt\"}"))
                .andExpect(status().isNotFound());

        // Merchant B cannot read Merchant A's payment
        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Merchant-Id", merchantB.getMerchantId().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    public void reviewerRoleCanDecideReviews() throws Exception {
        // Merchant A adds a REVIEWER member
        MerchantMemberDto reviewer = merchantMemberService.addMerchantMember(
                merchantA.getMerchantId(),
                new AddMerchantMemberRequest("p19-reviewer-" + UUID.randomUUID().toString().substring(0, 8) + "@mend.test",
                        "Pass123!", "Reviewer User", "REVIEWER"),
                userPrincipalResolver.resolveUser(merchantA.getUserId())
        );
        String reviewerToken = jwtService.generateToken(reviewer.getUserId(), reviewer.getEmail(), List.of("REVIEWER"));

        JsonNode response = runScenarioJson(tokenA, merchantA.getMerchantId(), "HIGH_RISK_HUMAN_REVIEW");
        String reviewId = response.get("reviewId").asText();

        mockMvc.perform(post("/api/v1/reviews/" + reviewId + "/approve")
                .header("Authorization", "Bearer " + reviewerToken)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"reviewer approval\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"));
    }

    @Test
    public void paymentSearchIsTenantScoped() throws Exception {
        runScenarioJson(tokenA, merchantA.getMerchantId(), "LOW_RISK_RETRY");

        MvcResult result = mockMvc.perform(get("/api/v1/payments")
                .header("Authorization", "Bearer " + tokenA)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .param("search", "pay_demo_"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(list.get("totalElements").asLong() >= 1);

        // Merchant B does not see Merchant A's demo payment
        MvcResult resultB = mockMvc.perform(get("/api/v1/payments")
                .header("Authorization", "Bearer " + tokenB)
                .header("X-Merchant-Id", merchantB.getMerchantId().toString())
                .param("search", "pay_demo_"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listB = objectMapper.readTree(resultB.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(0, listB.get("totalElements").asLong());
    }
}
