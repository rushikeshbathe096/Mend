package com.mend.integration;

import tools.jackson.databind.ObjectMapper;
import com.mend.dto.AddMerchantMemberRequest;
import com.mend.dto.BootstrapRequest;
import com.mend.dto.BootstrapResponse;
import com.mend.dto.MerchantMemberDto;
import com.mend.dto.UpdateMerchantConfigRequest;
import com.mend.security.JwtService;
import com.mend.security.SecurityFilter;
import com.mend.security.UserPrincipalResolver;
import com.mend.service.AuthService;
import com.mend.service.MerchantMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class MerchantConfigApiIntegrationTest extends AbstractIntegrationTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    private BootstrapResponse merchantA;
    private BootstrapResponse merchantB;

    private String merchantAAdminToken;
    private String merchantBAdminToken;
    private String merchantAViewerToken;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(securityFilter)
                .build();

        merchantA = authService.bootstrap(new BootstrapRequest("Alpha Corp", "admin@alpha.com", "Pass123!", "Alpha Admin"));
        merchantB = authService.bootstrap(new BootstrapRequest("Beta Corp", "admin@beta.com", "Pass123!", "Beta Admin"));

        merchantAAdminToken = jwtService.generateToken(merchantA.getUserId(), merchantA.getUserEmail(), List.of("MERCHANT_ADMIN"));
        merchantBAdminToken = jwtService.generateToken(merchantB.getUserId(), merchantB.getUserEmail(), List.of("MERCHANT_ADMIN"));

        // Create a non-admin (REVIEWER) user for Merchant A
        MerchantMemberDto viewerDto = merchantMemberService.addMerchantMember(
                merchantA.getMerchantId(),
                new AddMerchantMemberRequest("reviewer@alpha.com", "Pass123!", "Alpha Reviewer", "REVIEWER"),
                userPrincipalResolver.resolveUser(merchantA.getUserId())
        );

        merchantAViewerToken = jwtService.generateToken(viewerDto.getUserId(), viewerDto.getEmail(), List.of("REVIEWER"));
    }

    @Test
    public void testMerchantAdminCanGetConfig() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/config")
                .header("Authorization", "Bearer " + merchantAAdminToken)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId", equalTo(merchantA.getMerchantId().toString())))
                .andExpect(jsonPath("$.maxAttempts", equalTo(3)))
                .andExpect(jsonPath("$.contactWindowHours", equalTo(24)))
                .andExpect(jsonPath("$.retryStrategy", equalTo("EXPONENTIAL_BACKOFF")));
    }

    @Test
    public void testMerchantAdminCanPutConfig() throws Exception {
        UpdateMerchantConfigRequest request = new UpdateMerchantConfigRequest(5, 48, "SMART_AI");

        mockMvc.perform(put("/api/v1/merchants/config")
                .header("Authorization", "Bearer " + merchantAAdminToken)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId", equalTo(merchantA.getMerchantId().toString())))
                .andExpect(jsonPath("$.maxAttempts", equalTo(5)))
                .andExpect(jsonPath("$.contactWindowHours", equalTo(48)))
                .andExpect(jsonPath("$.retryStrategy", equalTo("SMART_AI")));
    }

    @Test
    public void testMerchantACannotAccessMerchantBConfig() throws Exception {
        // Merchant A admin trying to access Merchant B config via X-Merchant-Id header
        mockMvc.perform(get("/api/v1/merchants/config")
                .header("Authorization", "Bearer " + merchantAAdminToken)
                .header("X-Merchant-Id", merchantB.getMerchantId().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testMerchantACannotModifyMerchantBConfig() throws Exception {
        UpdateMerchantConfigRequest request = new UpdateMerchantConfigRequest(4, 12, "FIXED_INTERVAL");

        mockMvc.perform(put("/api/v1/merchants/config")
                .header("Authorization", "Bearer " + merchantAAdminToken)
                .header("X-Merchant-Id", merchantB.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testMissingJwtReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/config")
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testInvalidJwtReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/config")
                .header("Authorization", "Bearer invalid.jwt.token")
                .header("X-Merchant-Id", merchantA.getMerchantId().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testNonAdminUserCannotModifyConfiguration() throws Exception {
        UpdateMerchantConfigRequest request = new UpdateMerchantConfigRequest(5, 48, "SMART_AI");

        // Viewer user attempting to PUT config should receive 403 Forbidden
        mockMvc.perform(put("/api/v1/merchants/config")
                .header("Authorization", "Bearer " + merchantAViewerToken)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testInvalidConfigurationValuesReturn400() throws Exception {
        // Invalid maxAttempts (< 1)
        UpdateMerchantConfigRequest invalidMaxAttempts = new UpdateMerchantConfigRequest(0, 24, "SMART_AI");
        mockMvc.perform(put("/api/v1/merchants/config")
                .header("Authorization", "Bearer " + merchantAAdminToken)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidMaxAttempts)))
                .andExpect(status().isBadRequest());

        // Invalid contactWindowHours (> 168)
        UpdateMerchantConfigRequest invalidWindow = new UpdateMerchantConfigRequest(3, 500, "SMART_AI");
        mockMvc.perform(put("/api/v1/merchants/config")
                .header("Authorization", "Bearer " + merchantAAdminToken)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidWindow)))
                .andExpect(status().isBadRequest());

        // Blank retryStrategy
        UpdateMerchantConfigRequest blankStrategy = new UpdateMerchantConfigRequest(3, 24, "   ");
        mockMvc.perform(put("/api/v1/merchants/config")
                .header("Authorization", "Bearer " + merchantAAdminToken)
                .header("X-Merchant-Id", merchantA.getMerchantId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(blankStrategy)))
                .andExpect(status().isBadRequest());
    }
}
