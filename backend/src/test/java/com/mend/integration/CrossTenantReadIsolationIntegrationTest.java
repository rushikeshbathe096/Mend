package com.mend.integration;

import com.mend.domain.entity.AgentDecisionRecord;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.MerchantUser;
import com.mend.domain.entity.Role;
import com.mend.domain.entity.User;
import com.mend.domain.repository.AgentDecisionRecordRepository;
import com.mend.domain.repository.CampaignRepository;
import com.mend.domain.repository.MerchantRepository;
import com.mend.domain.repository.MerchantUserRepository;
import com.mend.domain.repository.RoleRepository;
import com.mend.domain.repository.UserRepository;
import com.mend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Pre-M1 regression tests: an authenticated user of merchant A must not be able to read
 * merchant B's agent decisions or audit trail through id- or query-parameter-scoped endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class CrossTenantReadIsolationIntegrationTest {

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
    private AgentDecisionRecordRepository agentDecisionRecordRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private UUID merchantAId;
    private UUID merchantBId;
    private String tokenA;
    private String tokenB;
    private UUID campaignBId;
    private String reasoningMarkerB;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.findByName("MERCHANT_ADMIN").orElseThrow();

        merchantAId = createMerchant("Isolation Merchant A");
        merchantBId = createMerchant("Isolation Merchant B");
        tokenA = createAdminToken(merchantAId, adminRole);
        tokenB = createAdminToken(merchantBId, adminRole);

        Campaign campaignB = new Campaign(UUID.randomUUID(), merchantBId);
        campaignB.setPaymentId("pay_isolation_" + UUID.randomUUID().toString().substring(0, 8));
        campaignBId = campaignRepository.save(campaignB).getId();

        reasoningMarkerB = "merchant-b-private-" + UUID.randomUUID();
        agentDecisionRecordRepository.save(new AgentDecisionRecord(
                UUID.randomUUID(), campaignBId, merchantBId, campaignB.getPaymentId(),
                "STRATEGY_SELECTION", "RETRY_IMMEDIATELY", new BigDecimal("0.5000"),
                reasoningMarkerB, null, null, null, null, false, null, null));
    }

    @Test
    @DisplayName("GET /agent/timeline?merchantId=<other merchant> is forbidden")
    void timelineQueryParamCannotSelectForeignMerchant() throws Exception {
        HttpResponse<String> foreign = get("/api/v1/agent/timeline?merchantId=" + merchantBId, tokenA);
        assertEquals(403, foreign.statusCode());
        assertFalse(foreign.body().contains(reasoningMarkerB));

        HttpResponse<String> own = get("/api/v1/agent/timeline?merchantId=" + merchantBId, tokenB);
        assertEquals(200, own.statusCode());
        assertTrue(own.body().contains(reasoningMarkerB));
    }

    @Test
    @DisplayName("GET /campaigns/{otherMerchantCampaign}/decisions returns no foreign records")
    void campaignDecisionsAreScopedToCallerMerchant() throws Exception {
        HttpResponse<String> foreign = get("/api/v1/campaigns/" + campaignBId + "/decisions", tokenA);
        assertEquals(200, foreign.statusCode());
        assertEquals("[]", foreign.body());

        HttpResponse<String> own = get("/api/v1/campaigns/" + campaignBId + "/decisions", tokenB);
        assertEquals(200, own.statusCode());
        assertTrue(own.body().contains(reasoningMarkerB));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private UUID createMerchant(String name) {
        Merchant merchant = new Merchant(UUID.randomUUID(), name);
        merchant.setStatus("ACTIVE");
        return merchantRepository.save(merchant).getId();
    }

    private String createAdminToken(UUID merchantId, Role adminRole) {
        String email = "isolation_" + UUID.randomUUID().toString().substring(0, 8) + "@mend.io";
        User user = new User(UUID.randomUUID(), email, "hashed_pass", "Isolation User");
        user.setStatus("ACTIVE");
        user = userRepository.save(user);
        merchantUserRepository.save(new MerchantUser(UUID.randomUUID(), merchantId, user.getId(), adminRole.getId()));
        return jwtService.generateToken(user.getId(), user.getEmail(), List.of("MERCHANT_ADMIN"));
    }
}
