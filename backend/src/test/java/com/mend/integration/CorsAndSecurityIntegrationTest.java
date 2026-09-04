package com.mend.integration;

import com.mend.dto.BootstrapRequest;
import com.mend.dto.BootstrapResponse;
import com.mend.security.JwtService;
import com.mend.security.SecurityFilter;
import com.mend.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class CorsAndSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private SecurityFilter securityFilter;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;
    private String validJwtToken;
    private UUID validMerchantId;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(securityFilter)
                .build();

        BootstrapResponse response = authService.bootstrap(
                new BootstrapRequest("CORS Merchant", "cors_admin@example.com", "Password123!", "CORS Admin")
        );
        validJwtToken = jwtService.generateToken(response.getUserId(), response.getUserEmail(), java.util.List.of("MERCHANT_ADMIN"));
        validMerchantId = response.getMerchantId();
    }

    @Test
    public void testOptionsPreflightSucceedsWithCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/campaigns")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization, X-Merchant-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("GET")));
    }

    @Test
    public void testAllowedOriginReceivesCorsHeadersOnGet() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns")
                .header("Origin", "http://localhost:3000")
                .header("Authorization", "Bearer " + validJwtToken)
                .header("X-Merchant-Id", validMerchantId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    public void testUnauthorizedGetReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns")
                .header("Origin", "http://localhost:3000"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testUnauthorizedPostReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/merchants/" + validMerchantId + "/members")
                .header("Origin", "http://localhost:3000")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"password\":\"Pass123!\",\"fullName\":\"Test\",\"roleName\":\"MERCHANT_VIEWER\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testCaseA_ValidJwtWithoutMerchantHeader_UsesPrimaryMerchant() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns")
                .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isOk());
    }

    @Test
    public void testCaseB_ValidJwtWithAuthorizedMerchantHeader_Succeeds() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns")
                .header("Authorization", "Bearer " + validJwtToken)
                .header("X-Merchant-Id", validMerchantId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    public void testCaseC_ValidJwtWithUnauthorizedMerchantHeader_Returns403() throws Exception {
        UUID unassociatedMerchantId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/campaigns")
                .header("Authorization", "Bearer " + validJwtToken)
                .header("X-Merchant-Id", unassociatedMerchantId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testCaseD_MissingJwtToken_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testCaseE_InvalidJwtToken_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns")
                .header("Authorization", "Bearer invalid.jwt.token.here"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testInvalidXMerchantIdFormatReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/campaigns")
                .header("Authorization", "Bearer " + validJwtToken)
                .header("X-Merchant-Id", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
