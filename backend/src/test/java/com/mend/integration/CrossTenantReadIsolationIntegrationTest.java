package com.mend.integration;

import com.mend.domain.entity.AuditLog;
import com.mend.domain.entity.Campaign;
import com.mend.domain.entity.Merchant;
import com.mend.domain.entity.MerchantUser;
import com.mend.domain.entity.Role;
import com.mend.domain.entity.User;
import com.mend.domain.repository.AuditLogRepository;
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
 * merchant B's campaign, payment detail or audit trail through id- or query-parameter-scoped endpoints.
 * M3 (ADR-003): the agent-decision endpoints are deleted; their two cases now cover the endpoints that show a
 * campaign's decisions instead (payment detail, campaign read).
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
    private AuditLogRepository auditLogRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private UUID merchantAId;
    private UUID merchantBId;
    private String tokenA;
    private String tokenB;
    private UUID campaignBId;
    private String paymentIdB;
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
        paymentIdB = campaignB.getPaymentId();

        reasoningMarkerB = "merchant-b-private-" + UUID.randomUUID();

        AuditLog auditB = new AuditLog(UUID.randomUUID(), "PAYMENT_FAILED");
        auditB.setMerchantId(merchantBId);
        auditB.setCampaignId(campaignBId);
        auditB.setActorType("SYSTEM");
        auditB.setReason(reasoningMarkerB);
        auditLogRepository.save(auditB);
    }

    @Test
    @DisplayName("GET /payments/{otherMerchantPayment} returns no foreign detail; the agent timeline is gone")
    void timelineQueryParamCannotSelectForeignMerchant() throws Exception {
        // M3 behaviour change: /agent/timeline is deleted (ADR-003 §3); it must not serve anything, to anyone
        assertNotEquals(200, get("/api/v1/agent/timeline?merchantId=" + merchantBId, tokenB).statusCode());

        HttpResponse<String> foreign = get("/api/v1/payments/" + paymentIdB, tokenA);
        assertNotEquals(200, foreign.statusCode());
        assertFalse(foreign.body().contains(campaignBId.toString()));

        HttpResponse<String> own = get("/api/v1/payments/" + paymentIdB, tokenB);
        assertEquals(200, own.statusCode());
        assertTrue(own.body().contains(campaignBId.toString()));
        assertTrue(own.body().contains(reasoningMarkerB), "B's own audit trail is in B's payment detail");
    }

    @Test
    @DisplayName("GET /campaigns/{otherMerchantCampaign} returns no foreign campaign; the decisions endpoint is gone")
    void campaignDecisionsAreScopedToCallerMerchant() throws Exception {
        // M3 behaviour change: /campaigns/{id}/decisions (agent decisions) is deleted (ADR-003 §3)
        assertNotEquals(200, get("/api/v1/campaigns/" + campaignBId + "/decisions", tokenB).statusCode());

        HttpResponse<String> foreign = get("/api/v1/campaigns/" + campaignBId, tokenA);
        assertNotEquals(200, foreign.statusCode());
        assertFalse(foreign.body().contains(paymentIdB));

        HttpResponse<String> own = get("/api/v1/campaigns/" + campaignBId, tokenB);
        assertEquals(200, own.statusCode());
        assertTrue(own.body().contains(paymentIdB));
    }

    @Test
    @DisplayName("GET /audit/campaign/{otherMerchantCampaign} returns no foreign audit rows")
    void campaignAuditLogsAreScopedToCallerMerchant() throws Exception {
        HttpResponse<String> foreign = get("/api/v1/audit/campaign/" + campaignBId, tokenA);
        assertEquals(200, foreign.statusCode());
        assertEquals("[]", foreign.body());

        HttpResponse<String> own = get("/api/v1/audit/campaign/" + campaignBId, tokenB);
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
