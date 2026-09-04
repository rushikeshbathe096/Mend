package com.mend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mend.client.AiClassificationClient;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.MerchantUser;
import com.mend.domain.entity.Role;
import com.mend.domain.entity.User;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.MerchantUserRepository;
import com.mend.domain.repository.RoleRepository;
import com.mend.domain.repository.UserRepository;
import com.mend.dto.ai.AgentOrchestrationRequestDto;
import com.mend.dto.ai.AgentOrchestrationResponseDto;
import com.mend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class AiAgentIntegrationTest {

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

    @MockitoBean
    private AiClassificationClient aiClassificationClient;

    private ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;
    private UUID merchantId;
    private UUID campaignId;
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
        campaignId = UUID.randomUUID();

        MerchantUser mu = new MerchantUser(UUID.randomUUID(), merchant.getId(), user.getId(), adminRole.getId());
        merchantUserRepository.save(mu);

        jwtToken = jwtService.generateToken(user.getId(), user.getEmail(), List.of("MERCHANT_ADMIN"));
    }

    @Test
    @DisplayName("POST /api/v1/agent/orchestrate - Successful Orchestration with JWT")
    void testAgentOrchestration_Success() throws Exception {
        AgentOrchestrationResponseDto mockResponse = new AgentOrchestrationResponseDto(
                "trace_12345",
                merchantId.toString(),
                campaignId.toString(),
                "pay_999",
                "RETRY_PAYMENT",
                new BigDecimal("0.95"),
                "LOW",
                "Transient network failure classified; immediate retry recommended.",
                List.of("CLASSIFICATION:NETWORK_FAILURE", "ATTEMPT:1"),
                false,
                "COMPLIANCE_ALLOWED",
                "EXECUTE",
                Map.of("status", "SUCCEEDED"),
                1,
                true
        );

        given(aiClassificationClient.orchestrateAgent(any(AgentOrchestrationRequestDto.class)))
                .willReturn(mockResponse);

        AgentOrchestrationRequestDto request = AgentOrchestrationRequestDto.of(
                merchantId, campaignId, "pay_999", UUID.randomUUID(),
                "network_failure", "Gateway timeout", 1000L, 1
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/agent/orchestrate"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-Merchant-Id", merchantId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("trace_12345"));
        assertTrue(response.body().contains("RETRY_PAYMENT"));
        assertTrue(response.body().contains("COMPLIANCE_ALLOWED"));
    }

    @Test
    @DisplayName("GET /api/v1/campaigns/{campaignId}/decisions - Retrieve Agent Decision History")
    void testGetCampaignDecisions() throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/campaigns/" + campaignId + "/decisions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-Merchant-Id", merchantId.toString())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("["));
    }

    @Test
    @DisplayName("POST /api/v1/agent/orchestrate - Missing JWT fails with 401")
    void testAgentOrchestration_MissingJwt() throws Exception {
        AgentOrchestrationRequestDto request = AgentOrchestrationRequestDto.of(
                merchantId, campaignId, "pay_999", UUID.randomUUID(),
                "network_failure", "Gateway timeout", 1000L, 1
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + "/api/v1/agent/orchestrate"))
                .header("Content-Type", "application/json")
                .header("X-Merchant-Id", merchantId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }
}

