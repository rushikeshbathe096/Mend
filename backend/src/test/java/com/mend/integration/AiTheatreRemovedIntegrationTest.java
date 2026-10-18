package com.mend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.MerchantUser;
import com.mend.domain.entity.Role;
import com.mend.domain.entity.User;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.MerchantUserRepository;
import com.mend.domain.repository.RoleRepository;
import com.mend.domain.repository.UserRepository;
import com.mend.security.JwtService;
import com.mend.testsupport.TestRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 behaviour change (ADR-003 §3): was AiAgentIntegrationTest, which asserted the "six-agent" orchestration endpoint and
 * the agent-decision history. Both are deleted; these cases assert that they are gone, that nothing writes agent decision
 * records, and that no API response carries an agent decision or a confidence (also not a legacy one).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class AiTheatreRemovedIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantUserRepository merchantUserRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;
    private UUID merchantId;
    private Campaign campaign;
    private String jwtToken;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();

        Role adminRole = roleRepository.findByName("MERCHANT_ADMIN")
                .orElseGet(() -> roleRepository.save(new Role(UUID.randomUUID(), "MERCHANT_ADMIN", "Admin")));

        String email = "agent_user_" + UUID.randomUUID().toString().substring(0, 8) + "@mend.io";
        User user = new User(UUID.randomUUID(), email, "hashed_pass", "Agent User");
        user.setStatus("ACTIVE");
        user = userRepository.save(user);

        Merchant merchant = new Merchant(UUID.randomUUID(), "Agent Merchant");
        merchant.setStatus("ACTIVE");
        merchant = merchantRepository.save(merchant);
        merchantId = merchant.getId();

        // a legacy (pre-M3) campaign that still carries an AI-era confidence in its column
        campaign = new Campaign(UUID.randomUUID(), merchantId);
        campaign.setPaymentId("pay_theatre_" + UUID.randomUUID().toString().substring(0, 8));
        campaign.setFailureClass("INSUFFICIENT_FUNDS");
        campaign.setConfidence(new BigDecimal("0.95"));
        campaign = campaignRepository.save(campaign);

        merchantUserRepository.save(new MerchantUser(UUID.randomUUID(), merchant.getId(), user.getId(), adminRole.getId()));
        jwtToken = jwtService.generateToken(user.getId(), user.getEmail(), List.of("MERCHANT_ADMIN"));
    }

    @AfterEach
    void tearDown() {
        TestRows.deleteMerchant(jdbc, merchantId);
    }

    @Test
    @DisplayName("POST /api/v1/agent/orchestrate is gone and writes no agent decision record")
    void testAgentOrchestration_Success() throws Exception {
        String body = "{\"merchantId\":\"" + merchantId + "\",\"campaignId\":\"" + campaign.getId() + "\",\"paymentId\":\""
                + campaign.getPaymentId() + "\",\"errorCode\":\"network_failure\",\"amount\":1000,\"attemptCount\":1}";
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/agent/orchestrate"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-Merchant-Id", merchantId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertEquals(404, response.statusCode(), response.body());
        assertFalse(response.body().contains("agentDecision"), response.body());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM agent_decision_records WHERE merchant_id = ?", Long.class, merchantId));
    }

    @Test
    @DisplayName("Agent decision history is gone; payment detail and campaign carry no agent decisions and no confidence")
    void testGetCampaignDecisions() throws Exception {
        assertEquals(404, get("/api/v1/campaigns/" + campaign.getId() + "/decisions").statusCode());

        HttpResponse<String> detail = get("/api/v1/payments/" + campaign.getPaymentId());
        assertEquals(200, detail.statusCode(), detail.body());
        JsonNode detailJson = objectMapper.readTree(detail.body());
        assertFalse(detailJson.has("agentDecisions"));
        assertEquals("INSUFFICIENT_FUNDS", detailJson.get("campaign").get("failureClass").asText());
        assertFalse(detailJson.get("campaign").has("confidence"), "the legacy 0.95 is not re-exposed");

        HttpResponse<String> campaignResponse = get("/api/v1/campaigns/" + campaign.getId());
        assertEquals(200, campaignResponse.statusCode(), campaignResponse.body());
        assertFalse(objectMapper.readTree(campaignResponse.body()).has("confidence"));
    }

    @Test
    @DisplayName("POST /api/v1/agent/orchestrate without JWT still fails with 401")
    void testAgentOrchestration_MissingJwt() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/agent/orchestrate"))
                .header("Content-Type", "application/json")
                .header("X-Merchant-Id", merchantId.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertEquals(401, response.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-Merchant-Id", merchantId.toString())
                .GET());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
